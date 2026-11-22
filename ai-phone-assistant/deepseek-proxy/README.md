# Private DeepSeek proxy for AI Phone Assistant 0.3

Deploy with Cloudflare Workers (requires your Cloudflare account):

```sh
cd ai-phone-assistant/deepseek-proxy
npx wrangler login
npx wrangler secret put DEEPSEEK_API_KEY
npx wrangler secret put ASSISTANT_ACCESS_TOKEN
npx wrangler deploy
```

Use a random, long ASSISTANT_ACCESS_TOKEN (different from your DeepSeek API key). Enter the deployed https://...workers.dev URL and the access token into the Android app. Keep both secrets out of GitHub, APK, chat and screenshots.

Security: proxy only returns an allowlisted action; Android shows a confirmation dialog before execution. This is a single-user MVP, not a multi-user service. For public deployment add per-user authentication, abuse prevention, quotas and server-side rate limiting. Cloudflare Worker must be deployed before AI commands can work.
