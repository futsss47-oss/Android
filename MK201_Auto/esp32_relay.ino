// Блок твердотельных реле для MK201 Auto: ESP32 -> резистор 330 Ом -> CPC1560GS (вывод 2, LED+),
// вывод 3 (LED-) на GND ESP32. Контакты реле — выводы 6 и 7. Выводы 5 (C-) и 8 (C+) не подключать.
// Команды (строка + \n):  AI n (1..8) | AO n (1..2) | OFF | PING | ID?
// Ответ: OK  (на ID? — MK201-RELAY)
// Переключение «сначала включить новое, потом выключить старое»: петля калибратора не рвётся.
// Сторожевой таймер: нет команд дольше WATCHDOG_MS -> все реле разомкнуты (программа на ПК зависла/упала).
const int PINS[10] = {16, 17, 18, 19, 21, 22, 23, 25, 26, 27};   // AI0..AI7, AO0, AO1
const bool ACTIVE_HIGH = true;                                   // HIGH = светодиод реле горит = контакт замкнут
const unsigned long WATCHDOG_MS = 5000;

unsigned long lastCmd = 0;
bool anyOn = false;

void setAll(bool on) {
  for (int p : PINS) digitalWrite(p, (on == ACTIVE_HIGH) ? HIGH : LOW);
  anyOn = on;
}

void select(int idx) {
  digitalWrite(PINS[idx], ACTIVE_HIGH ? HIGH : LOW);               // сначала включаем новый канал…
  for (int i = 0; i < 10; i++)
    if (i != idx) digitalWrite(PINS[i], ACTIVE_HIGH ? LOW : HIGH); // …затем выключаем остальные
  anyOn = true;
}

void setup() {
  for (int p : PINS) { pinMode(p, OUTPUT); }
  setAll(false);
  Serial.begin(115200);
  Serial.setTimeout(200);
  lastCmd = millis();
}

void loop() {
  if (anyOn && millis() - lastCmd > WATCHDOG_MS) setAll(false);   // сторожевой таймер
  if (!Serial.available()) return;
  String s = Serial.readStringUntil('\n'); s.trim(); s.toUpperCase();
  if (s.length() == 0) return;
  lastCmd = millis();
  int n = s.substring(3).toInt();
  if (s == "OFF") { setAll(false); Serial.println("OK"); }
  else if (s == "PING") { Serial.println("OK"); }
  else if (s == "ID?") { Serial.println("MK201-RELAY"); }
  else if (s.startsWith("AI ") && n >= 1 && n <= 8) { select(n - 1); Serial.println("OK"); }
  else if (s.startsWith("AO ") && n >= 1 && n <= 2) { select(8 + n - 1); Serial.println("OK"); }
  else Serial.println("ERR");
}
