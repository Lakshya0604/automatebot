# Relay setup (free)
1. console.firebase.google.com > create project > Build > Realtime Database > Create (Spark/free).
2. Rules:
```
{ "rules": { ".read": false, ".write": false,
  "rooms": { "$room": { ".read": "$room.length >= 24", ".write": "$room.length >= 24" } } } }
```
Anyone who knows the long random room code can send commands, nobody can list rooms.
3. Database URL used by Automatebot: https://lakshya-studio-3d-default-rtdb.firebaseio.com (in the Lakshya Digital Studio Firebase project). Put it in the app (Website control) and tap "Open web remote".
