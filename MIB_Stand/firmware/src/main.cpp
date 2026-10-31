/*
 * Блок коммутации мини-стенда для одиночной проверки барьеров MIB-211 / MIB-212.
 *
 * Плата: ESP32 DevKitC (ESP32-WROOM-32). Связь с ПК: USB (CP2102/CH340), 115200 8N1,
 * текстовые команды, строка заканчивается \n (\r игнорируется). Ответ: "OK ..." или "ERR текст".
 *
 * Реле (через ULN2003A, катушки 5 В):
 *   K1  IN1   вход канала 1 изделия (XP4) подключён к источнику CA71
 *   K2  IN2   вход канала 2 изделия (XP5) подключён к источнику CA71
 *   K3  OUT   выход на измеритель CA71: выкл - канал 1 (XP3:4), вкл - канал 2 (XP3:5);
 *             невыбранный канал при этом нагружен на 250 Ом
 *   K4  LOAD  последовательно с измерителем включается нагрузка 400 Ом
 *   K5  PWR   питание изделия +24 В (XP2)
 *   K6  SELF  самоконтроль стенда: источник CA71 замкнут на измеритель через тракт стенда
 *
 * Команды:
 *   ID?                   -> OK MIB-STAND,<версия>
 *   STATE?                -> OK PWR=0 IN=0 OUT=1 LOAD=0 SELF=0
 *   PWR 0|1               питание изделия
 *   IN 0|1|2              какой вход изделия подключён к источнику (0 - никакой)
 *   OUT 1|2               какой выход изделия подключён к измерителю
 *   LOAD 0|1              нагрузка 400 Ом
 *   ROUTE <канал>         IN=канал и OUT=канал одной командой, LOAD=0
 *   SELF 0|1              самоконтроль (только при PWR=0 и IN=0)
 *   I?                    -> OK <ток потребления, мА> <напряжение питания, В>
 *   LED OFF|BUSY|PASS|FAIL
 *   BEEP <мс>
 *   WDT <с>               отключить всё, если от ПК нет команд дольше <с> секунд (0 - выкл.)
 *   ALLOFF                всё выключить
 * Сообщения от стенда без запроса:  EV START, EV STOP (кнопки на передней панели),
 *   EV WDT (сработал сторожевой таймер связи), EV OVERCURRENT (ток потребления выше предела).
 */
#include <Arduino.h>
#include <Wire.h>

static const char *VERSION = "1.0";

// --- выводы (GPIO12 не используется: это вывод выбора напряжения флеш-памяти при старте) ---
static const int PIN_K1_IN1 = 25;
static const int PIN_K2_IN2 = 26;
static const int PIN_K3_OUT = 27;
static const int PIN_K4_LOAD = 14;
static const int PIN_K5_PWR = 13;
static const int PIN_K6_SELF = 18;
static const int PIN_LED_BUSY = 4;    // жёлтый
static const int PIN_LED_PASS = 16;   // зелёный
static const int PIN_LED_FAIL = 17;   // красный
static const int PIN_BUZZER = 19;     // активный зуммер через транзистор
static const int PIN_BTN_START = 32;  // кнопка на GND, подтяжка внутренняя
static const int PIN_BTN_STOP = 33;
static const int PIN_SDA = 21;
static const int PIN_SCL = 22;

// --- INA226 в цепи +24 В изделия (INA219 не годится: предел 26 В, а БП подстраивается до 26,4 В) ---
static const uint8_t INA_ADDR = 0x40;
static const float SHUNT_OHM = 0.1f;
static const float OVERCURRENT_MA = 300.0f;  // аварийное отключение питания изделия

static const uint32_t RELAY_SETTLE_MS = 15;  // время срабатывания сигнальных реле с запасом

struct State {
  bool pwr = false;
  int in = 0;      // 0, 1, 2
  int out = 1;     // 1, 2
  bool load = false;
  bool self = false;
} st;

static bool inaOk = false;
static uint32_t wdtMs = 0, lastCmdMs = 0, beepUntil = 0;
static String line;

// ------------------------------------------------------------ реле ----
static void applyRelays() {
  digitalWrite(PIN_K1_IN1, st.in == 1);
  digitalWrite(PIN_K2_IN2, st.in == 2);
  digitalWrite(PIN_K3_OUT, st.out == 2);
  digitalWrite(PIN_K4_LOAD, st.load);
  digitalWrite(PIN_K5_PWR, st.pwr);
  digitalWrite(PIN_K6_SELF, st.self);
}

// Смена входа: сначала разомкнуть оба входа, затем замкнуть нужный, чтобы источник
// ни на миг не оказался подключён к двум гальванически развязанным каналам сразу.
static void setInput(int ch) {
  if (st.in != 0 && st.in != ch) {
    st.in = 0;
    applyRelays();
    delay(RELAY_SETTLE_MS);
  }
  st.in = ch;
  applyRelays();
  delay(RELAY_SETTLE_MS);
}

static void allOff() {
  st = State();
  applyRelays();
  delay(RELAY_SETTLE_MS);
}

// ------------------------------------------------------------ INA226 ----
static bool inaWrite(uint8_t reg, uint16_t v) {
  Wire.beginTransmission(INA_ADDR);
  Wire.write(reg);
  Wire.write(v >> 8);
  Wire.write(v & 0xFF);
  return Wire.endTransmission() == 0;
}

static bool inaRead(uint8_t reg, uint16_t &v) {
  Wire.beginTransmission(INA_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)INA_ADDR, 2) != 2) return false;
  v = (uint16_t)Wire.read() << 8;
  v |= Wire.read();
  return true;
}

static void inaInit() {
  // усреднение 128 выборок, время преобразования 1,1 мс для шунта и шины, непрерывный режим
  inaOk = inaWrite(0x00, 0x0927);
}

static bool readSupply(float &mA, float &volts) {
  uint16_t sh, bus;
  if (!inaOk || !inaRead(0x01, sh) || !inaRead(0x02, bus)) return false;
  mA = (int16_t)sh * 0.0025f / SHUNT_OHM;  // 2,5 мкВ на единицу
  volts = bus * 0.00125f;                  // 1,25 мВ на единицу
  return true;
}

// ------------------------------------------------------------ индикация ----
static void setLeds(const String &mode) {
  digitalWrite(PIN_LED_BUSY, mode == "BUSY");
  digitalWrite(PIN_LED_PASS, mode == "PASS");
  digitalWrite(PIN_LED_FAIL, mode == "FAIL");
}

static void beep(uint32_t ms) {
  digitalWrite(PIN_BUZZER, HIGH);
  beepUntil = millis() + ms;
}

// ------------------------------------------------------------ кнопки ----
struct Button {
  int pin;
  const char *event;
  bool last = true;
  uint32_t changed = 0;
};
static Button buttons[] = {{PIN_BTN_START, "EV START"}, {PIN_BTN_STOP, "EV STOP"}};

static void pollButtons() {
  for (auto &b : buttons) {
    bool v = digitalRead(b.pin);
    if (v != b.last && millis() - b.changed > 40) {  // антидребезг 40 мс
      b.changed = millis();
      b.last = v;
      if (!v) {
        if (b.pin == PIN_BTN_STOP) allOff();  // СТОП отключает изделие даже без ПК
        Serial.println(b.event);
      }
    }
  }
}

// ------------------------------------------------------------ команды ----
static bool parseInt(const String &s, int lo, int hi, int &out) {
  if (s.length() == 0) return false;
  for (size_t i = 0; i < s.length(); i++)
    if (!isDigit(s[i])) return false;
  out = s.toInt();
  return out >= lo && out <= hi;
}

static void reply(const String &s) { Serial.println(s); }

static void handle(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;
  int sp = cmd.indexOf(' ');
  String name = sp < 0 ? cmd : cmd.substring(0, sp);
  String arg = sp < 0 ? "" : cmd.substring(sp + 1);
  name.toUpperCase();
  arg.trim();
  arg.toUpperCase();
  int n;

  if (name == "ID?") {
    reply(String("OK MIB-STAND,") + VERSION);
  } else if (name == "STATE?") {
    reply(String("OK PWR=") + st.pwr + " IN=" + st.in + " OUT=" + st.out + " LOAD=" + st.load +
          " SELF=" + st.self);
  } else if (name == "PWR" && parseInt(arg, 0, 1, n)) {
    if (n && st.self) return reply("ERR выключите самоконтроль");
    st.pwr = n;
    applyRelays();
    reply("OK");
  } else if (name == "IN" && parseInt(arg, 0, 2, n)) {
    if (n && st.self) return reply("ERR выключите самоконтроль");
    setInput(n);
    reply("OK");
  } else if (name == "OUT" && parseInt(arg, 1, 2, n)) {
    st.out = n;
    applyRelays();
    delay(RELAY_SETTLE_MS);
    reply("OK");
  } else if (name == "LOAD" && parseInt(arg, 0, 1, n)) {
    st.load = n;
    applyRelays();
    delay(RELAY_SETTLE_MS);
    reply("OK");
  } else if (name == "ROUTE" && parseInt(arg, 1, 2, n)) {
    if (st.self) return reply("ERR выключите самоконтроль");
    st.load = false;
    st.out = n;
    setInput(n);
    reply("OK");
  } else if (name == "SELF" && parseInt(arg, 0, 1, n)) {
    if (n && (st.pwr || st.in)) return reply("ERR самоконтроль только при PWR=0 и IN=0");
    st.self = n;
    applyRelays();
    delay(RELAY_SETTLE_MS);
    reply("OK");
  } else if (name == "I?") {
    float mA, v;
    if (!readSupply(mA, v)) return reply("ERR нет связи с INA226");
    reply(String("OK ") + String(mA, 1) + " " + String(v, 2));
  } else if (name == "LED" && (arg == "OFF" || arg == "BUSY" || arg == "PASS" || arg == "FAIL")) {
    setLeds(arg);
    reply("OK");
  } else if (name == "BEEP" && parseInt(arg, 1, 5000, n)) {
    beep(n);
    reply("OK");
  } else if (name == "WDT" && parseInt(arg, 0, 3600, n)) {
    wdtMs = (uint32_t)n * 1000;
    reply("OK");
  } else if (name == "ALLOFF") {
    allOff();
    reply("OK");
  } else {
    reply("ERR неизвестная команда или неверный параметр: " + cmd);
  }
}

// ------------------------------------------------------------ основное ----
void setup() {
  for (int p : {PIN_K1_IN1, PIN_K2_IN2, PIN_K3_OUT, PIN_K4_LOAD, PIN_K5_PWR, PIN_K6_SELF, PIN_LED_BUSY,
                PIN_LED_PASS, PIN_LED_FAIL, PIN_BUZZER}) {
    digitalWrite(p, LOW);
    pinMode(p, OUTPUT);
  }
  pinMode(PIN_BTN_START, INPUT_PULLUP);
  pinMode(PIN_BTN_STOP, INPUT_PULLUP);
  allOff();
  Serial.begin(115200);
  Wire.begin(PIN_SDA, PIN_SCL, 100000);
  inaInit();
  lastCmdMs = millis();
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      lastCmdMs = millis();
      handle(line);
      line = "";
    } else if (c != '\r' && line.length() < 64) {
      line += c;
    }
  }

  pollButtons();

  if (beepUntil && (int32_t)(millis() - beepUntil) >= 0) {
    digitalWrite(PIN_BUZZER, LOW);
    beepUntil = 0;
  }

  if (wdtMs && millis() - lastCmdMs > wdtMs && (st.pwr || st.in || st.self)) {
    allOff();
    setLeds("FAIL");
    Serial.println("EV WDT");
  }

  static uint32_t lastI = 0;
  if (st.pwr && millis() - lastI > 200) {
    lastI = millis();
    float mA, v;
    if (readSupply(mA, v) && mA > OVERCURRENT_MA) {
      allOff();
      setLeds("FAIL");
      Serial.println("EV OVERCURRENT");
    }
  }
}
