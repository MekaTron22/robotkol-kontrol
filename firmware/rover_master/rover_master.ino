/*
 * ROVER MASTER (Arduino Uno #1) – Bluetooth + sürüş + slave köprüsü
 *
 *  - HC-05 (SoftwareSerial, 9600) üzerinden Android uygulamasından komut alır
 *  - L293D ile 2 DC motoru sürer (çıplak entegre veya L293D motor shield)
 *  - Kol komutlarını I2C ile slave Uno'ya iletir, servo açılarını geri okur
 *  - 600 ms komut gelmezse motorları durdurur (güvenlik)
 *
 * Protokol ve bağlantılar: docs/PLAN.md
 */
#include <Wire.h>
#include <SoftwareSerial.h>

// ---------------- Motor sürücü seçimi ----------------
#define DRIVER_L293D_IC     1   // çıplak L293D entegresi / küçük modül
#define DRIVER_L293D_SHIELD 2   // Adafruit v1 uyumlu "L293D Motor Shield" (74HC595'li)
#define MOTOR_DRIVER DRIVER_L293D_IC

// Motor yönü ters dönüyorsa true yapın
const bool INVERT_LEFT  = false;
const bool INVERT_RIGHT = false;

// ---------------- Pinler ----------------
const uint8_t PIN_BT_RX   = 10;  // HC-05 TXD buraya
const uint8_t PIN_BT_TX   = 9;   // HC-05 RXD'ye (1k/2k bölücü ile)
const uint8_t PIN_LIGHT   = A1;
const uint8_t PIN_STATUS  = 13;

#if MOTOR_DRIVER == DRIVER_L293D_IC
const uint8_t PIN_EN_L = 5, PIN_IN1 = 7, PIN_IN2 = 8;   // sol motor
const uint8_t PIN_EN_R = 6, PIN_IN3 = 4, PIN_IN4 = 12;  // sağ motor
#else
// L293D shield: 74HC595 kaydırmalı yazmaç + PWM pinleri
const uint8_t SH_LATCH = 12, SH_CLK = 4, SH_ENABLE = 7, SH_DATA = 8;
const uint8_t PIN_PWM_M1 = 11, PIN_PWM_M2 = 3;          // sol = M1, sağ = M2
const uint8_t M1_A = 2, M1_B = 3, M2_A = 1, M2_B = 4;   // 74HC595 bit numaraları
uint8_t shieldLatch = 0;
#endif

// ---------------- Protokol sabitleri ----------------
const uint8_t  SLAVE_ADDR = 0x08;
const uint8_t  REG_POSE = 0x01, REG_SPEED = 0x02, REG_HOME = 0x03, REG_FREEZE = 0x04;
const uint8_t  STATUS_MAGIC = 0xA5;
const uint8_t  JOINTS = 6;

const uint16_t LINK_TIMEOUT_MS = 600;   // bu süre paket gelmezse motor dur
const uint16_t TELEMETRY_MS    = 400;
const uint8_t  RAMP_PER_10MS   = 25;    // PWM rampası: 0→255 ~100 ms (L293D tepe akımını sınırlar)

SoftwareSerial bt(PIN_BT_RX, PIN_BT_TX);

int  targetL = 0, targetR = 0;   // istenen PWM (-255..255)
int  outL = 0, outR = 0;         // rampalı çıkış
uint32_t lastPacketMs = 0;
bool linkAlive = false;
bool slaveOk = false;
uint8_t slaveAngles[JOINTS] = {90, 100, 50, 70, 90, 60};

// ---------------- Motor sürme ----------------
#if MOTOR_DRIVER == DRIVER_L293D_SHIELD
static void shieldWrite() {
  digitalWrite(SH_LATCH, LOW);
  shiftOut(SH_DATA, SH_CLK, MSBFIRST, shieldLatch);
  digitalWrite(SH_LATCH, HIGH);
}
static void shieldDir(uint8_t a, uint8_t b, int v) {
  shieldLatch &= ~(_BV(a) | _BV(b));
  if (v > 0) shieldLatch |= _BV(a);
  else if (v < 0) shieldLatch |= _BV(b);
}
#endif

static void driveRaw(int l, int r) {
  if (INVERT_LEFT)  l = -l;
  if (INVERT_RIGHT) r = -r;
#if MOTOR_DRIVER == DRIVER_L293D_IC
  digitalWrite(PIN_IN1, l > 0); digitalWrite(PIN_IN2, l < 0);
  digitalWrite(PIN_IN3, r > 0); digitalWrite(PIN_IN4, r < 0);
  analogWrite(PIN_EN_L, abs(l));
  analogWrite(PIN_EN_R, abs(r));
#else
  shieldDir(M1_A, M1_B, l);
  shieldDir(M2_A, M2_B, r);
  shieldWrite();
  analogWrite(PIN_PWM_M1, abs(l));
  analogWrite(PIN_PWM_M2, abs(r));
#endif
}

static int rampToward(int cur, int tgt) {
  // Yön değişiminde önce sıfıra iner (H-köprüsünü korur)
  if ((cur > 0 && tgt < 0) || (cur < 0 && tgt > 0)) tgt = 0;
  if (cur < tgt) return min(cur + RAMP_PER_10MS, tgt);
  if (cur > tgt) return max(cur - RAMP_PER_10MS, tgt);
  return cur;
}

static void stopMotorsNow() { targetL = targetR = outL = outR = 0; driveRaw(0, 0); }

// ---------------- Slave (I2C) ----------------
static bool slaveSend(uint8_t reg, const uint8_t *data, uint8_t n) {
  Wire.beginTransmission(SLAVE_ADDR);
  Wire.write(reg);
  if (n) Wire.write(data, n);
  return Wire.endTransmission() == 0;
}

static void slaveRead() {
  uint8_t got = Wire.requestFrom(SLAVE_ADDR, (uint8_t)(2 + JOINTS));
  if (got == 2 + JOINTS && Wire.read() == STATUS_MAGIC) {
    Wire.read();  // hareket bayrağı (şimdilik kullanılmıyor)
    for (uint8_t j = 0; j < JOINTS; j++) slaveAngles[j] = Wire.read();
    slaveOk = true;
  } else {
    while (Wire.available()) Wire.read();
    slaveOk = false;
  }
}

// ---------------- Bluetooth ayrıştırıcı ----------------
const uint8_t MAX_FIELDS = 8;
char frame[48];
uint8_t frameLen = 0;
bool inFrame = false;

static uint8_t splitFields(char *s, long *out) {
  // s: "J,90,100,..." → out[0..]: sayılar (komut harfi hariç)
  uint8_t n = 0;
  char *p = strchr(s, ',');
  while (p && n < MAX_FIELDS) {
    p++;
    out[n++] = strtol(p, NULL, 10);
    p = strchr(p, ',');
  }
  return n;
}

static long clampL(long v, long lo, long hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Çerçeve: "<GÖVDE*CS>"  CS = GÖVDE karakterlerinin XOR'u (2 haneli hex).
// SoftwareSerial gönderim yaparken alınan baytlar bozulabilir; bozuk çerçeve atılır.
static bool verifyChecksum(char *s) {
  char *star = strrchr(s, '*');
  if (!star) return false;
  uint8_t x = 0;
  for (char *p = s; p < star; p++) x ^= (uint8_t)*p;
  long cs = strtol(star + 1, NULL, 16);
  *star = 0;  // gövdeyi ayır
  return cs == x;
}

uint16_t badFrames = 0;
static void sendFrame(const char *body);

static void handleFrame(char *s) {
  if (!verifyChecksum(s)) { badFrames++; return; }
  long f[MAX_FIELDS];
  uint8_t n = splitFields(s, f);
  lastPacketMs = millis();
  linkAlive = true;

  switch (s[0]) {
    case 'D':
      if (n == 2) { targetL = clampL(f[0], -255, 255); targetR = clampL(f[1], -255, 255); }
      break;
    case 'J':
      if (n == JOINTS) {
        uint8_t a[JOINTS];
        for (uint8_t j = 0; j < JOINTS; j++) a[j] = (uint8_t)clampL(f[j], 0, 180);
        slaveSend(REG_POSE, a, JOINTS);
      }
      break;
    case 'V':
      if (n == 1) { uint8_t v = (uint8_t)clampL(f[0], 10, 250); slaveSend(REG_SPEED, &v, 1); }
      break;
    case 'L':
      if (n == 1) digitalWrite(PIN_LIGHT, f[0] ? HIGH : LOW);
      break;
    case 'Z':
      slaveSend(REG_HOME, NULL, 0);
      break;
    case 'X':
      stopMotorsNow();
      slaveSend(REG_FREEZE, NULL, 0);
      break;
    case '?':
      sendFrame("OK,ROVER,1");
      break;
    case 'H':
    default:
      break;
  }
}

static void sendFrame(const char *body) {
  uint8_t x = 0;
  for (const char *p = body; *p; p++) x ^= (uint8_t)*p;
  char tail[6];
  snprintf(tail, sizeof(tail), "*%02X>", x);
  bt.write('<'); bt.print(body); bt.print(tail); bt.write('\n');
}

static void pollBluetooth() {
  while (bt.available()) {
    char c = bt.read();
    if (c == '<') { inFrame = true; frameLen = 0; }
    else if (c == '>') {
      if (inFrame && frameLen > 0) { frame[frameLen] = 0; handleFrame(frame); }
      inFrame = false;
    } else if (inFrame) {
      if (frameLen < sizeof(frame) - 1) frame[frameLen++] = c;
      else inFrame = false;  // taşma: çerçeveyi at
    }
  }
}

static void sendTelemetry() {
  char body[40];
  snprintf(body, sizeof(body), "T,%u,%u,%u,%u,%u,%u,%u", slaveOk ? 1 : 0,
           slaveAngles[0], slaveAngles[1], slaveAngles[2], slaveAngles[3], slaveAngles[4], slaveAngles[5]);
  sendFrame(body);
}

// ---------------- Kurulum / döngü ----------------
void setup() {
#if MOTOR_DRIVER == DRIVER_L293D_IC
  pinMode(PIN_EN_L, OUTPUT); pinMode(PIN_IN1, OUTPUT); pinMode(PIN_IN2, OUTPUT);
  pinMode(PIN_EN_R, OUTPUT); pinMode(PIN_IN3, OUTPUT); pinMode(PIN_IN4, OUTPUT);
#else
  pinMode(SH_LATCH, OUTPUT); pinMode(SH_CLK, OUTPUT); pinMode(SH_DATA, OUTPUT); pinMode(SH_ENABLE, OUTPUT);
  pinMode(PIN_PWM_M1, OUTPUT); pinMode(PIN_PWM_M2, OUTPUT);
  shieldLatch = 0; shieldWrite();
  digitalWrite(SH_ENABLE, LOW);   // 74HC595 çıkışlarını etkinleştir
#endif
  stopMotorsNow();
  pinMode(PIN_LIGHT, OUTPUT);
  pinMode(PIN_STATUS, OUTPUT);

  Serial.begin(115200);           // USB hata ayıklama
  bt.begin(9600);                 // HC-05 varsayılan hızı
  Wire.begin();
  Wire.setClock(100000);
#if defined(WIRE_HAS_TIMEOUT)
  Wire.setWireTimeout(3000, true);  // slave takılırsa master kilitlenmesin
#endif
  delay(2000);                    // slave servoları sırayla bağlarken bekle
  slaveRead();
  Serial.println(slaveOk ? F("Slave bulundu") : F("Slave YOK - I2C kablolarini kontrol et"));
}

void loop() {
  uint32_t now = millis();
  pollBluetooth();

  // Güvenlik: bağlantı koptu mu?
  if (linkAlive && now - lastPacketMs > LINK_TIMEOUT_MS) {
    linkAlive = false;
    stopMotorsNow();
  }
  digitalWrite(PIN_STATUS, linkAlive);

  // 100 Hz motor rampası
  static uint32_t lastRamp = 0;
  if (now - lastRamp >= 10) {
    lastRamp = now;
    int nl = rampToward(outL, targetL), nr = rampToward(outR, targetR);
    if (nl != outL || nr != outR) { outL = nl; outR = nr; driveRaw(outL, outR); }
  }

  // Telemetri
  static uint32_t lastTel = 0;
  if (now - lastTel >= TELEMETRY_MS) {
    lastTel = now;
    slaveRead();
    if (linkAlive) sendTelemetry();
  }
}
