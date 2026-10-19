/*
 * Automatebot - ESP8266 IR blaster firmware (stage 2)
 * Board: WeMos D1 / D1 mini (ESP8266)
 *
 * Receives named commands from the phone app over WiFi and either blasts an
 * IR signal through an IR LED or clicks the relay (for a dumb on/off device
 * like a fan or lamp). Flash with Arduino IDE + IRremoteESP8266 library.
 *
 * HTTP API (phone and board on the same WiFi):
 *   GET /ir?name=<command>   -> run a named command, e.g. /ir?name=fan_power
 *   GET /ir/raw?protocol=NEC&code=0x20DF10EF&bits=32  -> send any raw IR code
 *   GET /health              -> "ok"
 */

#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <IRremoteESP8266.h>
#include <IRsend.h>
#include <IRrecv.h>
#include <IRutils.h>

// WeMos D1 mini pins: D2 = GPIO4 (IR LED), D1 = GPIO5 (relay IN)
#define IR_SEND_PIN 4
#define RELAY_PIN   5
#define IR_RECV_PIN 14   // D5 = GPIO14, TSOP1738 / VS1838B data pin

// ---- fill these in ----
const char* WIFI_SSID = "YOUR_WIFI_NAME";
const char* WIFI_PASS = "YOUR_WIFI_PASSWORD";
// -----------------------

IRsend irsend(IR_SEND_PIN);
IRrecv irrecv(IR_RECV_PIN, 1024, 50, true);   // big buffer so AC remotes (long frames) fit
decode_results results;
ESP8266WebServer server(80);

/*
 * Named commands the phone app calls by name. Two kinds:
 *  - CMD_IR:    send an IR code (protocol/code/bits from YOUR remote -
 *               capture once with the IRremoteESP8266 IRrecvDumpV2 example
 *               and a VS1838B receiver, then paste here)
 *  - CMD_RELAY: just flip the relay (works today, no IR LED needed)
 */
enum CmdType { CMD_IR, CMD_RELAY };
struct NamedCommand {
  const char* name;
  CmdType type;
  decode_type_t protocol;
  uint64_t code;
  uint16_t bits;
  bool relayState;
};

NamedCommand commands[] = {
  // Works immediately with the relay module you already have:
  { "fan_power", CMD_RELAY, decode_type_t::UNKNOWN, 0, 0, true },   // toggles below, see handleRelay
  // Examples to replace with YOUR captured IR codes once the IR LED arrives:
  { "ac_power",  CMD_IR, decode_type_t::NEC, 0x20DF10EF, 32, false },
  { "tv_power",  CMD_IR, decode_type_t::NEC, 0x20DF10EF, 32, false },
};
const size_t NUM_COMMANDS = sizeof(commands) / sizeof(commands[0]);

bool relayOn = false;

void setRelay(bool on) {
  relayOn = on;
  // most relay boards are active-LOW: LOW = relay energized. Flip if yours isn't.
  digitalWrite(RELAY_PIN, on ? LOW : HIGH);
}

void handleHealth() {
  server.send(200, "text/plain", "ok");
}

void handleIr() {
  if (!server.hasArg("name")) {
    server.send(400, "text/plain", "missing name");
    return;
  }
  String name = server.arg("name");
  for (size_t i = 0; i < NUM_COMMANDS; i++) {
    if (name.equals(commands[i].name)) {
      if (commands[i].type == CMD_RELAY) {
        setRelay(!relayOn);   // named power commands toggle, like a remote's power button
        server.send(200, "text/plain", String("relay ") + (relayOn ? "on" : "off"));
      } else {
        irsend.send(commands[i].protocol, commands[i].code, commands[i].bits);
        server.send(200, "text/plain", "sent " + name);
      }
      return;
    }
  }
  server.send(404, "text/plain", "unknown command: " + name);
}

void handleIrRaw() {
  if (!server.hasArg("protocol") || !server.hasArg("code")) {
    server.send(400, "text/plain", "need protocol and code");
    return;
  }
  String protocol = server.arg("protocol");
  uint64_t code = strtoull(server.arg("code").c_str(), NULL, 0);
  uint16_t bits = server.hasArg("bits") ? server.arg("bits").toInt() : 32;
  decode_type_t proto = decode_type_t::UNKNOWN;
  if (protocol.equalsIgnoreCase("NEC")) proto = decode_type_t::NEC;
  else if (protocol.equalsIgnoreCase("SONY")) proto = decode_type_t::SONY;
  else if (protocol.equalsIgnoreCase("RC5")) proto = decode_type_t::RC5;
  else if (protocol.equalsIgnoreCase("SAMSUNG")) proto = decode_type_t::SAMSUNG;
  if (proto == decode_type_t::UNKNOWN) {
    server.send(400, "text/plain", "unsupported protocol");
    return;
  }
  irsend.send(proto, code, bits);
  server.send(200, "text/plain", "sent raw");
}

// GET /learn?timeout=10  -> waits for you to press a remote button at the receiver.
// Returns "freq,us,us,us,..." (raw mark/space timings in microseconds). Works for any brand,
// AC remotes included, because it stores the raw pulses and does not need the protocol.
void handleLearn() {
  int secs = server.hasArg("timeout") ? server.arg("timeout").toInt() : 10;
  if (secs < 2) secs = 2;
  if (secs > 20) secs = 20;
  irrecv.resume();
  unsigned long end = millis() + (unsigned long)secs * 1000UL;
  while (millis() < end) {
    if (irrecv.decode(&results)) {
      if (results.rawlen > 6) {
        String out = "38000";
        for (uint16_t i = 1; i < results.rawlen; i++) {
          out += ",";
          out += String((uint32_t)results.rawbuf[i] * kRawTick);
        }
        server.send(200, "text/plain", out);
        irrecv.resume();
        return;
      }
      irrecv.resume();   // ignore noise
    }
    delay(5);
    yield();
  }
  server.send(408, "text/plain", "nothing received");
}

// POST /send  body "freq,us,us,..." -> replays a learned signal
void handleSend() {
  String body = server.arg("plain");
  if (body.length() < 10) { server.send(400, "text/plain", "empty"); return; }
  int comma = body.indexOf(',');
  uint16_t khz = body.substring(0, comma).toInt() / 1000;
  if (khz < 20 || khz > 60) khz = 38;
  static uint16_t buf[1024];
  uint16_t n = 0;
  int pos = comma + 1;
  while (pos < (int)body.length() && n < 1024) {
    int next = body.indexOf(',', pos);
    if (next < 0) next = body.length();
    buf[n++] = (uint16_t)body.substring(pos, next).toInt();
    pos = next + 1;
  }
  irsend.sendRaw(buf, n, khz);
  server.send(200, "text/plain", "sent " + String(n));
}

void setup() {
  Serial.begin(115200);
  pinMode(RELAY_PIN, OUTPUT);
  setRelay(false);
  irsend.begin();
  irrecv.enableIRIn();

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Connecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  Serial.print("IP: ");
  Serial.println(WiFi.localIP());   // enter this IP in the phone app

  server.on("/health", handleHealth);
  server.on("/ir", handleIr);
  server.on("/ir/raw", handleIrRaw);
  server.on("/learn", handleLearn);
  server.on("/send", HTTP_POST, handleSend);
  server.begin();
}

void loop() {
  server.handleClient();
}
