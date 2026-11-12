// ESP32 вместо модуля MK211 в проверке CAN контроллера MK201 (прошивка CAN-test).
//
// Что известно из прошивок CAN-test (MK201) и MK211 v0.1.6.1:
//   * CANopen, 1 Мбит/с. MK201 — мастер, адрес 0x7F (127), heartbeat 500 мс (кадр 0x77F).
//   * Мастер ждёт модули: узел 1 — MK211, 2 — MK234, 3 — MK241, 4 — MK242, 5 — MK243, 6 — MK245.
//   * Увидел heartbeat узла -> читает код изделия (SDO 0x1018 sub 2, 4 байта) и сверяет:
//       узел 1: 100211032 или 100211000;  2: 100234010;  3: 100241032;  4: 100242032;  5: 100243032;  6: 100245008
//     -> пишет 0x1017 = 500 мс (heartbeat узла) -> пишет 0x1016 -> настройка по типу модуля
//     (для MK211: 24 + 8 записей настроек входов, чтение 0x6120 — 4 байта, чтение 0x6401 sub 1..8 — по 2 байта)
//     -> команда NMT «старт» (кадр 0x000: 01 <узел>). У настоящего MK211 после этого STAT горит зелёным.
//
// ESP32 изображает такой узел: шлёт heartbeat, отвечает на SDO, принимает NMT. Если MK201 дошёл до «старта»,
// значит его CAN и передаёт (мы получили его запросы), и принимает (каждый следующий запрос он шлёт только
// после нашего ответа) -> RESULT=OK.
//
// Железо: ESP32 + трансивер CAN на 3,3 В (плата SN65HVD230).
//   GPIO 5 -> TX (D) трансивера, GPIO 4 <- RX (R) трансивера, 3V3, GND.
//   CANH / CANL -> клеммы CAN MK201, общий провод (GND) соединить. Между CANH и CANL — 120 Ом
//   (на плате SN65HVD230 резистор обычно уже есть; на стороне MK201 — как при работе с MK211).
//
// Команды по USB (115200 бод, строка + Enter):
//   ID?        -> MK201-CAN
//   NODE n     — адрес узла 1..6 (по умолчанию 1 = MK211), состояние сбрасывается
//   PROD n     — код изделия вручную (по умолчанию — по адресу, см. таблицу)
//   STATUS?    -> одна строка: что уже произошло и RESULT=OK / WAIT
//   RESET      — начать проверку заново (то же, что NODE с текущим адресом)
//   LISTEN     — только слушать шину (ничего не передаёт, не подтверждает) и печатать все кадры
//   EMU        — обратно в режим эмулятора
//   LOG 0 / 1  — печатать каждый кадр (по умолчанию 1)

#include "driver/twai.h"

static const gpio_num_t PIN_TX = GPIO_NUM_5;
static const gpio_num_t PIN_RX = GPIO_NUM_4;
static const uint8_t MASTER_ID = 0x7F;

static const uint32_t PRODUCT[7] = {0, 100211032UL, 100234010UL, 100241032UL, 100242032UL, 100243032UL, 100245008UL};

uint8_t  nodeId = 1;
uint32_t productCode = PRODUCT[1];
bool     listenOnly = false, logFrames = true, driverOn = false;

// состояние проверки
uint8_t  nmtState = 0x7F;            // 0x7F — pre-operational, 0x05 — operational, 0x04 — stopped
uint16_t hbPeriod = 1000;            // мс; мастер перезапишет через 0x1017
uint32_t lastHb = 0, lastMasterHb = 0, tStart = 0;
bool     seenMasterHb = false, seenIdent = false, seenHbSet = false, seenConsSet = false, seenStart = false;
uint16_t cntDownload = 0, cntUpload = 0, cntRx = 0, cntTx = 0, cntTxFail = 0, cntPdo = 0;

void canStart(bool listen) {
  if (driverOn) { twai_stop(); twai_driver_uninstall(); driverOn = false; }
  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(PIN_TX, PIN_RX, listen ? TWAI_MODE_LISTEN_ONLY : TWAI_MODE_NORMAL);
  g.rx_queue_len = 64; g.tx_queue_len = 16;
  twai_timing_config_t t = TWAI_TIMING_CONFIG_1MBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (twai_driver_install(&g, &t, &f) != ESP_OK || twai_start() != ESP_OK) { Serial.println("ERR CAN driver"); return; }
  driverOn = true;
}

void printFrame(const char *dir, const twai_message_t &m, const char *note) {
  if (!logFrames) return;
  Serial.printf("%s %03X [%d]", dir, (unsigned)m.identifier, m.data_length_code);
  for (int i = 0; i < m.data_length_code; i++) Serial.printf(" %02X", m.data[i]);
  if (note && *note) { Serial.print("  | "); Serial.print(note); }
  Serial.println();
}

bool canSend(uint32_t id, const uint8_t *d, uint8_t len, const char *note) {
  twai_message_t m = {};
  m.identifier = id; m.data_length_code = len;
  memcpy(m.data, d, len);
  bool ok = twai_transmit(&m, pdMS_TO_TICKS(20)) == ESP_OK;
  if (ok) cntTx++; else cntTxFail++;
  printFrame(ok ? "TX" : "TX-FAIL", m, note);
  return ok;
}

void sendHeartbeat(bool bootup) {
  uint8_t d[1] = { bootup ? (uint8_t)0x00 : nmtState };
  canSend(0x700 + nodeId, d, 1, bootup ? "boot-up" : (nmtState == 0x05 ? "heartbeat OPERATIONAL" : "heartbeat pre-op"));
  lastHb = millis();
}

void resetTest() {
  nmtState = 0x7F; hbPeriod = 1000;
  seenMasterHb = seenIdent = seenHbSet = seenConsSet = seenStart = false;
  cntDownload = cntUpload = cntRx = cntTx = cntTxFail = cntPdo = 0;
  tStart = millis();
  if (!listenOnly) sendHeartbeat(true);
}

// длина ответа на чтение SDO (мастер проверяет длину): 0x6401 — 2 байта, 0x1017 — 2, 0x1001 — 1, остальное — 4
uint8_t uploadLen(uint16_t idx) {
  if (idx == 0x6401 || idx == 0x1017) return 2;
  if (idx == 0x1001) return 1;
  return 4;
}

uint32_t uploadValue(uint16_t idx, uint8_t sub) {
  if (idx == 0x1018) {
    if (sub == 0) return 4;
    if (sub == 2) return productCode;
    return 0;
  }
  if (idx == 0x1000) return 0x000F0191;      // профиль 401 (ввод-вывод), значение условное
  if (idx == 0x1017) return hbPeriod;
  return 0;                                   // входы «0»
}

void handleSdo(const twai_message_t &m) {
  if (m.data_length_code < 4) return;
  uint8_t cs = m.data[0];
  uint16_t idx = m.data[1] | (m.data[2] << 8);
  uint8_t sub = m.data[3];
  uint8_t r[8] = {0, m.data[1], m.data[2], m.data[3], 0, 0, 0, 0};
  char note[64];
  if ((cs & 0xE0) == 0x40) {                 // чтение (upload)
    uint8_t n = uploadLen(idx);
    uint32_t v = uploadValue(idx, sub);
    r[0] = 0x43 | ((4 - n) << 2);            // ускоренная передача, n байт данных
    for (uint8_t i = 0; i < n; i++) r[4 + i] = (v >> (8 * i)) & 0xFF;
    cntUpload++;
    if (idx == 0x1018 && sub == 2) seenIdent = true;
    snprintf(note, sizeof note, "SDO read %04X:%02X -> %lu", idx, sub, (unsigned long)v);
  } else if ((cs & 0xE0) == 0x20) {          // запись (download)
    if (!(cs & 0x02)) {                      // не ускоренная (сегментами) — не поддерживаем
      r[0] = 0x80; r[4] = 0x00; r[5] = 0x00; r[6] = 0x01; r[7] = 0x06;   // abort 0x06010000
      canSend(0x580 + nodeId, r, 8, "SDO abort (segmented)");
      return;
    }
    uint8_t n = (cs & 0x01) ? 4 - ((cs >> 2) & 3) : 4;
    uint32_t v = 0;
    for (uint8_t i = 0; i < n; i++) v |= (uint32_t)m.data[4 + i] << (8 * i);
    if (idx == 0x1017) { hbPeriod = (v >= 50 && v <= 60000) ? v : hbPeriod; seenHbSet = true; }
    if (idx == 0x1016) seenConsSet = true;
    r[0] = 0x60;
    cntDownload++;
    snprintf(note, sizeof note, "SDO write %04X:%02X = %lu", idx, sub, (unsigned long)v);
  } else {
    return;                                   // прочее (abort от мастера и т. п.)
  }
  canSend(0x580 + nodeId, r, 8, note);
}

void handleFrame(const twai_message_t &m) {
  cntRx++;
  uint32_t id = m.identifier;
  const char *note = "";
  if (id == 0x700 + MASTER_ID) { seenMasterHb = true; lastMasterHb = millis(); note = "master heartbeat"; }
  else if (id == 0x000) note = "NMT";
  else if (id == 0x600 + nodeId) note = "SDO request";
  else if (id >= 0x180 && id < 0x580) { cntPdo++; note = "PDO"; }
  printFrame("RX", m, note);
  if (listenOnly) return;
  if (id == 0x000 && m.data_length_code >= 2 && (m.data[1] == nodeId || m.data[1] == 0)) {
    switch (m.data[0]) {
      case 0x01: nmtState = 0x05; seenStart = true; Serial.println("NMT: START -> OPERATIONAL"); sendHeartbeat(false); break;
      case 0x02: nmtState = 0x04; Serial.println("NMT: STOP"); break;
      case 0x80: nmtState = 0x7F; Serial.println("NMT: PRE-OPERATIONAL"); break;
      case 0x81: case 0x82: Serial.println("NMT: RESET"); nmtState = 0x7F; sendHeartbeat(true); break;
    }
  } else if (id == 0x600 + nodeId) {
    handleSdo(m);
  }
}

void printStatus() {
  bool ok = seenMasterHb && seenIdent && seenStart;
  Serial.printf("STATUS node=%u prod=%lu mode=%s state=%s master_hb=%d ident=%d hb_set=%d cons_set=%d writes=%u reads=%u "
                "nmt_start=%d pdo=%u rx=%u tx=%u tx_fail=%u t=%lus RESULT=%s\n",
                nodeId, (unsigned long)productCode, listenOnly ? "LISTEN" : "EMU",
                nmtState == 0x05 ? "OPER" : nmtState == 0x04 ? "STOP" : "PREOP",
                seenMasterHb, seenIdent, seenHbSet, seenConsSet, cntDownload, cntUpload, seenStart, cntPdo,
                cntRx, cntTx, cntTxFail, (unsigned long)((millis() - tStart) / 1000), ok ? "OK" : "WAIT");
}

void handleCommand(String s) {
  s.trim(); s.toUpperCase();
  if (s == "ID?") Serial.println("MK201-CAN");
  else if (s == "STATUS?") printStatus();
  else if (s == "RESET") { resetTest(); Serial.println("OK RESET"); }
  else if (s == "LISTEN") { listenOnly = true; canStart(true); resetTest(); Serial.println("OK LISTEN"); }
  else if (s == "EMU") { listenOnly = false; canStart(false); resetTest(); Serial.println("OK EMU"); }
  else if (s.startsWith("LOG")) { logFrames = s.substring(3).toInt() != 0; Serial.println(logFrames ? "OK LOG 1" : "OK LOG 0"); }
  else if (s.startsWith("NODE")) {
    int n = s.substring(4).toInt();
    if (n < 1 || n > 126) { Serial.println("ERR NODE 1..126"); return; }
    nodeId = n; productCode = n <= 6 ? PRODUCT[n] : PRODUCT[1];
    resetTest(); Serial.printf("OK NODE %u PROD %lu\n", nodeId, (unsigned long)productCode);
  }
  else if (s.startsWith("PROD")) { productCode = strtoul(s.substring(4).c_str(), NULL, 10); resetTest(); Serial.printf("OK PROD %lu\n", (unsigned long)productCode); }
  else if (s.length()) Serial.println("ERR ?");
}

void setup() {
  Serial.begin(115200);
  delay(300);
  canStart(false);
  resetTest();
  Serial.println("MK201-CAN ready: node 1 (MK211), 1 Mbit/s");
}

void loop() {
  static String line;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') { handleCommand(line); line = ""; }
    else if (line.length() < 40) line += c;
  }
  if (!driverOn) { delay(200); canStart(listenOnly); return; }
  twai_message_t m;
  while (twai_receive(&m, 0) == ESP_OK) {
    if (!m.extd && !m.rtr) handleFrame(m);
  }
  twai_status_info_t st;
  if (twai_get_status_info(&st) == ESP_OK && st.state == TWAI_STATE_BUS_OFF) {
    Serial.println("CAN BUS-OFF -> restart (check wiring, 120 Ohm, speed)");
    canStart(listenOnly);
  }
  if (!listenOnly && millis() - lastHb >= hbPeriod) sendHeartbeat(false);
  delay(1);
}
