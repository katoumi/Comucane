#include <SoftwareSerial.h>

// Bluetooth Module Pins: RX=2, TX=3
SoftwareSerial BT(2, 3);

// Ultrasonic Sensor Pins
const int trigPin = 9;
const int echoPin = 10;

// Buzzer Pin
const int buzzer = 11;

long duration;
int distance;

void setup() {
  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);
  pinMode(buzzer, OUTPUT);

  // Bluetooth module usually operates at 9600 baud
  BT.begin(9600);

  // Optional: For serial monitor debugging via USB
  Serial.begin(9600);
}

void loop() {
  // Trigger ultrasonic sensor
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  // Read echo pulse (30ms timeout)
  duration = pulseIn(echoPin, HIGH, 30000);

  if (duration == 0) {
    distance = 999; // No object detected within range
  } else {
    distance = duration * 0.0343 / 2;
  }

  // Send distance to Android with prefix
  // The app will receive strings like "DIST:45"
  BT.print("DIST:");
  BT.println(distance);

  // Debug output to Serial Monitor
  Serial.print("Distance: ");
  Serial.println(distance);

  // Local Buzzer feedback logic
  if (distance <= 10) {
    // DANGER: Continuous beep
    tone(buzzer, 1000);
  } else if (distance <= 25) {
    // WARNING: Fast beeping
    tone(buzzer, 1000);
    delay(100);
    noTone(buzzer);
    delay(100);
  } else if (distance <= 50) {
    // CAUTION: Slow beeping
    tone(buzzer, 1000);
    delay(250);
    noTone(buzzer);
    delay(250);
  } else {
    // CLEAR: Silent
    noTone(buzzer);
  }

  // Small delay before next reading
  delay(100);
}
