#include <SoftwareSerial.h>

// ==========================================================
// 1. MAPEO DE HARDWARE (PINES)
// ==========================================================
const int PIN_VENTILADOR = 4; // Salida: Relé Coolers (12V)
const int PIN_LED_LISTO  = 5; // Salida: LED "Listo" (5V)
const int PIN_UV         = 7; // Salida: Relé Lámpara UV-C (220V)
const int PIN_BT_RX      = 8; // Conectar a TXD del Módulo Bluetooth
const int PIN_BT_TX      = 9; // Conectar a RXD del Módulo Bluetooth

SoftwareSerial btSerial(PIN_BT_RX, PIN_BT_TX);

// ==========================================================
// 2. ESTADOS
// ==========================================================
const int ENCENDER = HIGH;
const int APAGAR   = LOW;

void setup() {
  // Configuración de salidas
  pinMode(PIN_VENTILADOR, OUTPUT);
  pinMode(PIN_UV, OUTPUT);
  pinMode(PIN_LED_LISTO, OUTPUT);
 
  // Apagar cargas de potencia por seguridad
  apagarTodoHardware();
  
  // LED indicador "Listo" encendido permanentemente al energizar
  digitalWrite(PIN_LED_LISTO, HIGH);
 
  // Inicialización de comunicaciones
  Serial.begin(9600);  
  btSerial.begin(9600);
 
  Serial.println("======================================");
  Serial.println("         PURABOX OS v1.0             ");
  Serial.println("======================================");
}

void loop() {
  // --- PROCESAMIENTO DE COMANDOS BLUETOOTH ---
  if (btSerial.available()) {
    String comando = btSerial.readStringUntil('\n');
    comando.trim();

    if (comando.length() > 0) {
      Serial.print("[BT-IN] "); Serial.println(comando);

      if (comando == "STATUS_CHECK") {
         btSerial.println("READY");
         Serial.println("[STATUS] Respondido READY a la App");
      }
      else if (comando == "ABORTAR") {
        apagarTodoHardware();
        Serial.println("[ACTION] DETENCIÓN DE EMERGENCIA POR APP");
      }
      else if (comando == "VENTILAR_ON") {
        digitalWrite(PIN_VENTILADOR, ENCENDER);
        Serial.println("[ACTION] Coolers: ON");
      }
      else if (comando == "VENTILAR_OFF") {
        digitalWrite(PIN_VENTILADOR, APAGAR);
        Serial.println("[ACTION] Coolers: OFF");
      }
      else if (comando == "UV_ON") {
        digitalWrite(PIN_UV, ENCENDER);
        Serial.println("[ACTION] LUZ UV: ON");
      }
      else if (comando == "UV_OFF") {
        digitalWrite(PIN_UV, APAGAR);
        Serial.println("[ACTION] LUZ UV: OFF");
      }
    }
  }
}

// Función auxiliar para desenergizar los relés
void apagarTodoHardware() {
  digitalWrite(PIN_VENTILADOR, APAGAR);
  digitalWrite(PIN_UV, APAGAR);
}
