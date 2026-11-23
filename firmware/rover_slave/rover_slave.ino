/*
 * ROVER SLAVE (Arduino Uno #2) – 7 servo robot kol sürücüsü
 *
 * Master Uno'dan I2C (adres 0x08) üzerinden hedef açıları alır ve servoları
 * ayarlanabilir hızda (derece/saniye) yumuşakça hedefe götürür.
 * Omuz iki servoyla (A1, A2) ters yönde çalışır: A2 = 180 - A1 + trim.
 *
 * Bağlantı: bkz. docs/PLAN.md
 */
#include <Wire.h>
#include <Servo.h>

// ---------------- Yapılandırma ----------------
const uint8_t I2C_ADDR = 0x08;

enum Joint : uint8_t { J_ROOT = 0, J_SHOULDER, J_ELBOW, J_WRIST_PITCH, J_WRIST_ROLL, J_GRIP, J_COUNT };

// Servo pinleri (omuz için A1; A2 ayrı)
const uint8_t PIN_ROOT        = 3;
const uint8_t PIN_SHOULDER_A1 = 5;
const uint8_t PIN_SHOULDER_A2 = 6;
const uint8_t PIN_ELBOW       = 9;
const uint8_t PIN_WRIST_PITCH = 10;
const uint8_t PIN_WRIST_ROLL  = 11;
const uint8_t PIN_GRIP        = 12;

// Eklem sınırları (derece). Mekanik çarpışmayı önlemek için sahada daraltın.
const uint8_t JOINT_MIN[J_COUNT]  = {   0,  15,   0,   0,   0,  20 };
const uint8_t JOINT_MAX[J_COUNT]  = { 180, 165, 180, 180, 180, 110 };
// Ev (park) pozisyonu – uygulamadaki HOME ile aynı olmalı
const uint8_t JOINT_HOME[J_COUNT] = {  90, 100,  50,  70,  90,  60 };

// A2 servosunun A1'e göre mekanik montaj farkı (derece, + / -)
const int8_t SHOULDER_A2_TRIM = 0;

const uint8_t DEFAULT_SPEED = 90;   // derece / saniye
const uint8_t MIN_SPEED = 10, MAX_SPEED = 250;

// I2C kayıtları (master ile aynı)
const uint8_t REG_POSE = 0x01, REG_SPEED = 0x02, REG_HOME = 0x03, REG_FREEZE = 0x04;
const uint8_t STATUS_MAGIC = 0xA5;

// ---------------- Durum ----------------
Servo servoRoot, servoA1, servoA2, servoElbow, servoWristP, servoWristR, servoGrip;

float   current[J_COUNT];                 // mevcut (rampalı) açı
volatile uint8_t target[J_COUNT];        // hedef açı (ISR yazar)
volatile uint8_t currentByte[J_COUNT];   // ISR'nin güvenle okuyacağı yuvarlanmış mevcut açı
volatile uint8_t speedDps = DEFAULT_SPEED;
volatile bool    freezeReq = false;

static uint8_t clampJoint(uint8_t j, int v) {
  if (v < JOINT_MIN[j]) v = JOINT_MIN[j];
  if (v > JOINT_MAX[j]) v = JOINT_MAX[j];
  return (uint8_t)v;
}

static uint8_t mirrorA2(int a1) {
  int a2 = 180 - a1 + SHOULDER_A2_TRIM;
  if (a2 < 0) a2 = 0;
  if (a2 > 180) a2 = 180;
  return (uint8_t)a2;
}

// Mikrosaniye cinsinden yazmak 1° yerine ~0.1° çözünürlük verir (daha az titreme)
static int angleToUs(float a) { return (int)(544.0f + a * (2400.0f - 544.0f) / 180.0f); }

static void writeJoint(uint8_t j, float a) {
  int us = angleToUs(a);
  switch (j) {
    case J_ROOT:        servoRoot.writeMicroseconds(us); break;
    case J_SHOULDER:
      servoA1.writeMicroseconds(us);
      servoA2.writeMicroseconds(angleToUs(180.0f - a + SHOULDER_A2_TRIM));
      break;
    case J_ELBOW:       servoElbow.writeMicroseconds(us); break;
    case J_WRIST_PITCH: servoWristP.writeMicroseconds(us); break;
    case J_WRIST_ROLL:  servoWristR.writeMicroseconds(us); break;
    case J_GRIP:        servoGrip.writeMicroseconds(us); break;
  }
}

// ---------------- I2C ----------------
void onReceive(int n) {
  if (n < 1) return;
  uint8_t reg = Wire.read(); n--;
  switch (reg) {
    case REG_POSE:
      for (uint8_t j = 0; j < J_COUNT && n > 0; j++, n--) target[j] = clampJoint(j, Wire.read());
      break;
    case REG_SPEED:
      if (n > 0) { uint8_t s = Wire.read(); n--; speedDps = s < MIN_SPEED ? MIN_SPEED : (s > MAX_SPEED ? MAX_SPEED : s); }
      break;
    case REG_HOME:
      for (uint8_t j = 0; j < J_COUNT; j++) target[j] = JOINT_HOME[j];
      break;
    case REG_FREEZE:
      freezeReq = true;
      break;
  }
  while (Wire.available()) Wire.read();   // artakalanı at
}

void onRequest() {
  uint8_t buf[2 + J_COUNT];
  bool moving = false;
  buf[0] = STATUS_MAGIC;
  for (uint8_t j = 0; j < J_COUNT; j++) {
    buf[2 + j] = currentByte[j];
    if (buf[2 + j] != target[j]) moving = true;
  }
  buf[1] = moving ? 1 : 0;
  Wire.write(buf, sizeof(buf));
}

// ---------------- Kurulum ----------------
static void attachStaggered(Servo &s, uint8_t pin, int us) {
  s.writeMicroseconds(us);  // attach'tan önce yazılırsa ilk darbe doğru konumda olur
  s.attach(pin, 544, 2400);
  delay(250);               // sıralı bağlama: ani akım darbesini dağıt
}

void setup() {
  for (uint8_t j = 0; j < J_COUNT; j++) { current[j] = JOINT_HOME[j]; currentByte[j] = JOINT_HOME[j]; target[j] = JOINT_HOME[j]; }

  attachStaggered(servoRoot,   PIN_ROOT,        angleToUs(JOINT_HOME[J_ROOT]));
  attachStaggered(servoA1,     PIN_SHOULDER_A1, angleToUs(JOINT_HOME[J_SHOULDER]));
  attachStaggered(servoA2,     PIN_SHOULDER_A2, angleToUs(mirrorA2(JOINT_HOME[J_SHOULDER])));
  attachStaggered(servoElbow,  PIN_ELBOW,       angleToUs(JOINT_HOME[J_ELBOW]));
  attachStaggered(servoWristP, PIN_WRIST_PITCH, angleToUs(JOINT_HOME[J_WRIST_PITCH]));
  attachStaggered(servoWristR, PIN_WRIST_ROLL,  angleToUs(JOINT_HOME[J_WRIST_ROLL]));
  attachStaggered(servoGrip,   PIN_GRIP,        angleToUs(JOINT_HOME[J_GRIP]));

  Wire.begin(I2C_ADDR);
  Wire.onReceive(onReceive);
  Wire.onRequest(onRequest);
}

// ---------------- Ana döngü: rampa ----------------
void loop() {
  static uint32_t last = millis();
  uint32_t now = millis();
  uint32_t dt = now - last;
  if (dt < 10) return;          // 100 Hz güncelleme
  last = now;

  if (freezeReq) {
    noInterrupts();
    for (uint8_t j = 0; j < J_COUNT; j++) target[j] = currentByte[j];
    freezeReq = false;
    interrupts();
  }

  float step = speedDps * dt / 1000.0f;
  for (uint8_t j = 0; j < J_COUNT; j++) {
    float t = target[j];
    float c = current[j];
    if (c == t) continue;
    if (fabs(t - c) <= step) c = t;
    else c += (t > c) ? step : -step;
    current[j] = c;
    currentByte[j] = (uint8_t)(c + 0.5f);
    writeJoint(j, c);
  }
}
