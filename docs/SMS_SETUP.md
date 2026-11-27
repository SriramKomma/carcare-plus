# Real SMS for the live dispatch demo

Without any setup, the demo uses **pretend phones** in the browser
(`/live-demo` → "Open customer + phones side by side"). They go through exactly the
same code as real texts, so the demo works with no account and no internet.

For **real texts to the repairers' own phones** you need two things:

1. **Twilio** sends and receives the SMS.
2. **A tunnel** (ngrok) gives Twilio a public address for the replies, because the
   CarCare+ server only listens on this computer.

## 1. Twilio

1. Create an account at twilio.com (a trial account is enough for the demo).
2. Get a phone number that can send SMS (Console → Phone Numbers). A Canadian +1 number is simplest.
3. **Trial accounts can only text numbers you have verified.** Add every repairer's
   number from `config/repairers.txt` under Console → Phone Numbers → Verified Caller IDs.
   Each teammate gets a code by text and reads it to you.
   (Trial texts also start with "Sent from your Twilio trial account".)
4. Note the **Account SID** and **Auth Token** (Console home page). Keep the token secret:
   never put it in a file in git.

## 2. Tunnel

```
brew install ngrok
ngrok config add-authtoken <your ngrok token>     # free ngrok account
ngrok http 8080
```

ngrok prints an address such as `https://abc123.ngrok-free.app`. In Twilio, open your
number → Messaging → "A message comes in" → Webhook, **HTTP POST**:

```
https://abc123.ngrok-free.app/api/sms/incoming
```

(On the free plan the address can change each time ngrok starts; update it in Twilio and
in `TWILIO_WEBHOOK_URL` when it does.)

Safety: everything that arrives through the tunnel is refused except
`/api/sms/incoming`, and that route only believes requests signed by Twilio with your
auth token (`TwilioSmsGateway.isGenuine`).

## 3. Start CarCare+ with real SMS

In the terminal that runs the server (not in a file):

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export TWILIO_AUTH_TOKEN=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export TWILIO_FROM_NUMBER=+15195550199
export TWILIO_WEBHOOK_URL=https://abc123.ngrok-free.app/api/sms/incoming
mvn -q compile exec:java -Dexec.args="web"
```

The console should say `SMS: Twilio, from +1...`. Open http://localhost:8080/live-demo:
the banner says **Real SMS is on**.

## What the repairers can reply

| Reply | Meaning |
|---|---|
| `ACCEPTED` (also accept, yes, ok) | take my open request; first one wins |
| `NO` (also decline, pass) | not for me |
| `ACCEPTED 7` | choose JOB-7 when several are open |
| `STOP` | handled by Twilio (opt-out); CarCare+ never answers it |

If something fails, the customer's screen shows **SMS failed** next to that repairer;
hover over it for Twilio's reason (most often: the number is not verified on a trial account).
