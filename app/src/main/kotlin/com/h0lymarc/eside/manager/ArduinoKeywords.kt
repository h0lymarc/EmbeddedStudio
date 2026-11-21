package com.h0lymarc.eside.manager

object ArduinoKeywords {

    private val cpp = listOf(
        "auto", "bool", "break", "case", "char", "class", "const", "constexpr", "continue",
        "default", "delete", "do", "double", "else", "enum", "extern", "false", "float", "for",
        "if", "inline", "int", "long", "namespace", "new", "nullptr", "private", "protected",
        "public", "return", "short", "signed", "sizeof", "static", "struct", "switch",
        "template", "this", "true", "typedef", "unsigned", "void", "volatile", "while",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t", "int8_t", "int16_t", "int32_t",
        "int64_t", "size_t", "String", "byte", "word", "boolean"
    )

    private val arduino = listOf(
        // sketch + I/O
        "setup", "loop", "pinMode", "digitalWrite", "digitalRead", "analogRead", "analogWrite",
        "analogReference", "delay", "delayMicroseconds", "millis", "micros", "tone", "noTone",
        "pulseIn", "pulseInLong", "shiftIn", "shiftOut", "attachInterrupt", "detachInterrupt",
        "digitalPinToInterrupt", "interrupts", "noInterrupts",
        // math + bits
        "map", "constrain", "min", "max", "abs", "pow", "sqrt", "sq", "random", "randomSeed",
        "bitRead", "bitWrite", "bitSet", "bitClear", "bit", "lowByte", "highByte",
        // constants
        "HIGH", "LOW", "INPUT", "OUTPUT", "INPUT_PULLUP", "LED_BUILTIN", "A0", "A1", "A2",
        "A3", "A4", "A5", "PI", "HALF_PI", "TWO_PI", "DEC", "HEX", "OCT", "BIN",
        "RISING", "FALLING", "CHANGE", "LSBFIRST", "MSBFIRST",
        // Serial
        "Serial", "Serial1", "Serial2", "begin", "end", "print", "println", "printf",
        "available", "read", "write", "flush", "readString", "readStringUntil", "parseInt",
        "parseFloat", "setTimeout",
        // common libraries
        "Wire", "beginTransmission", "endTransmission", "requestFrom", "onReceive", "onRequest",
        "SPI", "transfer", "EEPROM", "put", "get", "update",
        "Servo", "attach", "detach", "writeMicroseconds", "attached",
        "LiquidCrystal", "LiquidCrystal_I2C", "setCursor", "clear", "home", "backlight",
        "noBacklight", "createChar", "SoftwareSerial"
    )

    private val esp32 = listOf(
        // WiFi
        "WiFi", "WiFiClient", "WiFiClientSecure", "WiFiServer", "WiFiUDP", "WiFiMulti",
        "WL_CONNECTED", "WL_DISCONNECTED", "WL_NO_SSID_AVAIL", "WIFI_STA", "WIFI_AP",
        "WIFI_AP_STA", "softAP", "softAPIP", "localIP", "status", "disconnect", "scanNetworks",
        "SSID", "RSSI", "macAddress", "setHostname", "mode", "reconnect",
        // web server + HTTP
        "WebServer", "handleClient", "send", "arg", "hasArg", "uri", "onNotFound", "on",
        "HTTPClient", "GET", "POST", "getString", "getSize", "addHeader", "HTTP_GET",
        "HTTP_POST", "ESPmDNS", "MDNS", "ArduinoOTA", "Update",
        // storage
        "SPIFFS", "LittleFS", "Preferences", "putString", "getString", "putInt", "getInt",
        "putBool", "getBool", "remove",
        // Bluetooth
        "BluetoothSerial", "BLEDevice", "BLEServer", "BLEUtils", "BLEService",
        "BLECharacteristic", "BLE2902", "BLEAdvertising",
        // PWM, touch, DAC
        "ledcSetup", "ledcAttachPin", "ledcAttach", "ledcWrite", "ledcRead", "ledcDetachPin",
        "dacWrite", "touchRead", "touchAttachInterrupt", "temperatureRead",
        // system + power
        "ESP", "getFreeHeap", "getChipModel", "restart", "esp_restart", "esp_random",
        "esp_sleep_enable_timer_wakeup", "esp_sleep_enable_ext0_wakeup", "esp_deep_sleep_start",
        "esp_light_sleep_start", "IRAM_ATTR",
        // FreeRTOS
        "xTaskCreate", "xTaskCreatePinnedToCore", "vTaskDelay", "vTaskDelete", "pdMS_TO_TICKS",
        "portTICK_PERIOD_MS", "xSemaphoreCreateMutex", "xSemaphoreTake", "xSemaphoreGive",
        "xQueueCreate", "xQueueSend", "xQueueReceive", "TaskHandle_t", "SemaphoreHandle_t",
        "QueueHandle_t",
        // pins
        "GPIO_NUM_2", "GPIO_NUM_4", "BUILTIN_LED"
    )

    fun forBoard(board: Board): Array<String> =
        (cpp + arduino + if (board == Board.ESP32) esp32 else emptyList())
            .distinct()
            .sorted()
            .toTypedArray()
}