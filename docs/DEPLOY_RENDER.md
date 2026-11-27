# Deploying CarCare+ on Render

Render runs the app as one Java program that stays on, which the live dispatch demo
needs: the job board and its first-accept lock live in that program's memory.
(Vercel cannot do this: it runs short functions, each possibly a fresh copy.)

What is in the repo for this:

| File | What it does |
|---|---|
| `Dockerfile` | builds the app with Java 21 and runs `Main web` |
| `scripts/build-data.sh` | during the build: downloads OpenStreetMap shops, crawls the NRCan guides, parses |
| `render.yaml` | tells Render: one free web service, hosted mode on, a random demo key |
| `.dockerignore` | keeps `data/`, `target/` and the real `config/repairers.txt` out of the image |

## 1. Create the service (once)

1. Sign in at https://dashboard.render.com with your GitHub account.
2. **New → Blueprint**, pick the `carcare-plus` repository, and press **Apply**.
   Render reads `render.yaml` and starts the first build. It takes several minutes:
   it downloads the shop data and crawls the guides (politely, 3 s between pages).
3. When the build log ends with `CarCare+ is running (hosted)`, the site is live at
   `https://carcare-plus.onrender.com` (Render shows the exact address).

Every push to `main` deploys again by itself (`autoDeploy: true`).

## 2. The demo link

The search pages are open to everyone. The **live dispatch demo is locked** behind a
secret, so strangers cannot accept jobs as a repairer or make the server send texts.

1. In the service, open **Environment** and copy the value of `CARCARE_DEMO_KEY`.
2. The demo link is:

   ```
   https://carcare-plus.onrender.com/live-demo?key=<CARCARE_DEMO_KEY>
   ```

Give that link only to the people who should use the demo (your team, the professor).
To lock everyone out again, change the value; Render restarts the service.

## 3. Optional: real SMS

On Render no tunnel is needed: Twilio can reach the site directly.

1. Under **Environment**, add `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER`
   and `TWILIO_WEBHOOK_URL=https://carcare-plus.onrender.com/api/sms/incoming`.
2. In Twilio, set the number's "A message comes in" webhook to that same address (HTTP POST).
3. The real phone numbers: under **Secret Files**, add a file named `repairers.txt`
   with the contents of your local `config/repairers.txt`, and add the variable
   `CARCARE_REPAIRERS_FILE=/etc/secrets/repairers.txt`.
   Without it the site uses `config/repairers.example.txt` (made-up 555 numbers).

See [SMS_SETUP.md](SMS_SETUP.md) for the Twilio trial details (verified numbers).

## Things to know

- **The free plan sleeps** after about 15 minutes without visitors; the next visit
  wakes it in about a minute. Open the site a few minutes before presenting.
- **Memory only:** jobs, pretend-phone messages and visit requests are lost when the
  service restarts or redeploys. That is fine for a demo.
- **Data comes from the build:** if OpenStreetMap or the NRCan site is down during a
  build, the build still finishes, with fewer pages. Redeploy later
  (Manual Deploy → Clear build cache & deploy) to try again.
