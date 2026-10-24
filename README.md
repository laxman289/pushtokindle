# Kindle Morning Digest (Android App)

An Android app that collects your **X (Twitter) Following tab** and **Manorama Online (മലയാളം) top news**, curates and summarizes them using **Gemini AI**, renders a **360×480pt zero-zoom PDF** engineered specifically for the **Kindle Paperwhite**, and emails it automatically to your `@kindle.com` address every morning.

---

## Key Features

1. **X (Twitter) Following Tab Scraper (`XFeedScraper.kt`)**:
   - Uses an embedded `WebView` with persistent cookies so you don't need a paid X API key.
   - Sign in once using the **"Log in to X (One-Time Setup)"** button.
   - Automatically opens `https://x.com/home`, clicks the **Following** tab, scrolls through recent posts, filters out promoted ads, and captures tweets across virtualized DOM scrolls.
2. **Manorama Online Malayalam Top News (`ManoramaScraper.kt`)**:
   - Scrapes top stories from `https://www.manoramaonline.com` (`a.cmp-story-list__title-link`) and fetches each article's full Malayalam `articleBody` from its `NewsArticle` JSON-LD metadata.
3. **Gemini AI Curation (`GeminiCuratorService.kt`)**:
   - Uses `gemini-2.5-flash` to filter out noise from your X Following feed and summarize Manorama Online top stories **strictly in Malayalam script (`മലയാളം`)**.
   - Includes a full offline/no-key fallback if no Gemini API key is configured.
4. **Zero-Zoom Kindle Paperwhite PDF (`KindlePdfGenerator.kt`)**:
   - Renders on a custom **360 × 480 pt (3:4 aspect ratio)** canvas matched to the Kindle Paperwhite screen with `13.5pt` body typography and `18pt` margins so you never have to pinch-to-zoom.
   - Uses Android's native `StaticLayout` + HarfBuzz shaping so Malayalam conjuncts and chillaksharam render cleanly.
   - Starts **Part 2: മനോരമ ഓൺലൈൻ — പ്രധാന വാർത്തകൾ** on a separate page break.
5. **Automatic Send-to-Kindle Email (`KindleEmailSender.kt`)**:
   - Emails the generated PDF to your `@kindle.com` address over SMTP (`smtp.gmail.com:587`).

---

## Setup Guide

### 1. Amazon Kindle Email Setup
1. Go to **Amazon Manage Your Content and Devices → Preferences → Personal Document Settings**.
2. Note your Kindle Paperwhite's email address (e.g., `yourname_123@kindle.com`).
3. Scroll to **Approved Personal Document E-mail List** and add your sender Gmail address (e.g., `yourname@gmail.com`).

### 2. Gmail App Password
1. Go to your Google Account → **Security → 2-Step Verification → App passwords**.
2. Create an App Password named `Kindle Digest` and paste the 16-character password into the app.

### 3. Build the APK
- **Option A (Android Studio)**: Open the `kindle-morning-digest` folder in Android Studio and click **Run** or **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
- **Option B (GitHub Actions — No local install required)**: Push this folder to a GitHub repository; the included `.github/workflows/build-apk.yml` workflow will automatically build and upload `Kindle-Morning-Digest-APK` (`app-debug.apk`).
