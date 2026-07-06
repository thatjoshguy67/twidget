# Twidget Bridge

Tiny Rettiwt bridge for Twidget. It fetches public X/Twitter profile details with Rettiwt guest auth, normalizes the response for Android, and caches results briefly.

## Local

```bash
npm install
npm run dev
```

Test:

```bash
curl http://localhost:8787/health
curl http://localhost:8787/user/thatjoshguy69
```

## Environment

- `PORT`: Railway provides this automatically. Local default is `8787`.
- `CACHE_TTL_SECONDS`: profile cache TTL. Default `900`.
- `RATE_LIMIT_WINDOW_SECONDS`: per-IP rate-limit window. Default `60`.
- `RATE_LIMIT_MAX`: max requests per window. Default `60`.
- `RETTIWT_API_KEY`: optional server-side Rettiwt user-auth key. Do not put this in the Android app.
- `X_CLIENT_ID`: X OAuth 2.0 client ID. Required for `/oauth/x/start`.
- `X_CLIENT_SECRET`: optional X OAuth client secret for confidential clients.
- `X_CALLBACK_URL`: public callback URL registered in the X developer portal, for example `https://your-railway-app.up.railway.app/oauth/x/callback`.
- `X_OAUTH_SCOPES`: optional OAuth scope override. Default `users.read tweet.read offline.access`.
- `TWIDGET_ANDROID_RETURN_URI`: Android app return URI. Default `twidget://oauth/x`.
