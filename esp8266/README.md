# ESP8266 WiFi IR blaster + relay (stage 2)

The phone app sends named commands to a WeMos D1 (ESP8266) over WiFi. The
board either blasts an infrared signal (AC, old TV, anything with an IR
remote) or clicks a relay (dumb on/off devices like a fan or lamp).

## What you have vs what you need

Already owned: WeMos D1 (ESP8266), 1-channel relay module, DS18B20 probe.
Still to buy (cheap, ~50-100 INR total): 940nm IR LED, ~100 ohm resistor,
2N2222 transistor (for range). Optional: VS1838B IR receiver to capture your
remotes' codes.

Good news: the relay works TODAY with this firmware - wire it up and
"fan on/off" can toggle the relay while you wait for the IR LED.

## Wiring (text diagram)

### Relay (works now)

```
WeMos D1          Relay module          Fan / lamp
--------          ------------          ----------
  D1 (GPIO5) --->  IN
  5V --------->    VCC
  GND --------->   GND
                   COM  ---- one wire of the fan's power cord
                   NO   ---- (other side of that cut wire)
```
The relay sits in series on ONE wire of the device's mains cord, like a
switch. COM + NO = on when energized. MAINS VOLTAGE - unplug everything
while wiring, tape/insulate the joints, and ask someone who has done mains
wiring if unsure.

### IR LED (when it arrives)

```
WeMos D1
--------
  D2 (GPIO4) ---[100 ohm]--- base of 2N2222
                              collector --- IR LED cathode (-, short leg)
                              emitter   --- GND
  3.3V --- IR LED anode (+, long leg)
  GND  --- GND
```
Point the IR LED at the device you want to control, within a few metres,
line of sight.

## Flash

1. Arduino IDE > add ESP8266 board support (Boards Manager URL:
   http://arduino.esp8266.com/stable/package_esp8266com_index.json),
   board: "LOLIN(WEMOS) D1 R2 & mini"
2. Library Manager > install `IRremoteESP8266`
3. Open `ir_blaster/ir_blaster.ino`, set your WiFi name/password
4. Flash, open Serial Monitor at 115200, note the IP it prints
5. Enter that IP in the phone app's ESP8266 field

## Protocol

- `GET /ir?name=<name>` - run a named command from the `commands[]` table
  (the phone app calls this for "AC on", "fan off", ...)
- `GET /ir/raw?protocol=NEC&code=0x20DF10EF&bits=32` - send any raw IR code
- `GET /health` - sanity check

To learn your own remotes' codes: flash the IRremoteESP8266 `IRrecvDumpV2`
example with a VS1838B wired to D5, point the remote at it, press the
button, and copy the printed protocol/code/bits into `commands[]`.
