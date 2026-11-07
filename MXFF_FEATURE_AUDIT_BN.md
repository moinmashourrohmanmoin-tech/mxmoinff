# MXFF AI — ফিচার অডিট ও বাস্তবায়ন পরিকল্পনা

এই নথি `MXFF-AI-source-shadow.zip`-এর বর্তমান সোর্স কোড পরীক্ষা করে তৈরি। এটি কোনো APK-কে পরীক্ষিত/সম্পূর্ণ বলে দাবি করে না।

## বর্তমানে সোর্সে যা পাওয়া গেছে

| ফিচার | সোর্সে অবস্থা | গুরুত্বপূর্ণ শর্ত |
|---|---|---|
| বাংলা/ইংরেজি ভয়েস ইনপুট | আছে | Android Speech Recognizer ও মাইক্রোফোন অনুমতি দরকার |
| AI-এর মুখে উত্তর (TTS) | আছে | ফোনে বাংলা TTS voice ইনস্টল থাকলে ভালো কাজ করবে |
| Floating orb / hologram overlay | আছে | Display over other apps অনুমতি ও foreground service দরকার |
| “Hey MXFF” / “Jarvis” wake phrase | আছে | Android speech recognizer loop; নেটওয়ার্ক/ব্যাটারি লাগতে পারে; সব ফোনে সবসময় নির্ভরযোগ্য নয় |
| অ্যাপ খোলা | আছে | পরিচিত package mapping বা launcher app label match |
| Google / YouTube search | আছে | ইন্টারনেট দরকার; গান/ভিডিও চালানো সার্চ ফল ও অ্যাপের ওপর নির্ভরশীল |
| Flashlight, volume, alarm, timer, media key | আছে | ডিভাইস/অন্য অ্যাপের সমর্থনের ওপর নির্ভরশীল |
| SMS / কল / WhatsApp action | আংশিক | সংবেদনশীল কাজের আগে confirmation; প্রয়োজনীয় permission/অ্যাপ দরকার |
| Notification থেকে সাম্প্রতিক লেখা পড়া | আংশিক | Notification Access চালু থাকতে হবে; শুধু notification-এ আসা preview পাওয়া যায় |
| Chat history / settings | Web app-এ আছে | ব্রাউজার/অ্যাপ storage-এর ওপর নির্ভরশীল |

## ব্যবহারকারীর চাওয়া ফিচার—যেগুলো এখনো সম্পূর্ণ নেই

1. **যেকোনো অ্যাপের স্ক্রিন দেখা/বোঝা:** বর্তমান কোডে MediaProjection screen-capture flow বা on-device screen understanding নেই। Overlay থাকা মানেই স্ক্রিন দেখা নয়।
2. **TikTok-এর নির্দিষ্ট ভিডিও/গান চালানো:** বর্তমানে YouTube search ও Android media key আছে; TikTok-এর ভিতরে সার্চ বক্সে লেখা, নির্দিষ্ট গান নির্বাচন বা ভিডিও feed নিয়ন্ত্রণের Accessibility automation নেই।
3. **TikTok Inbox খুলে নির্দিষ্ট বন্ধুর DM পড়া:** Notification Listener কেবল notification preview ধরে; অ্যাপের ভিতরের সম্পূর্ণ inbox/পুরনো DM পড়তে পারে না।
4. **DM লেখা/পাঠানো:** TikTok-এর UI-তে টাইপ/পাঠানোর নির্ভরযোগ্য automation নেই। ভবিষ্যতে Accessibility Service লাগতে পারে; পাঠানোর আগে আলাদা user confirmation বাধ্যতামূলক রাখা উচিত।
5. **Play Store থেকে অ্যাপ নিজে ইনস্টল:** Android সাধারণত ব্যবহারকারীর confirmation ছাড়া নীরবে অন্য অ্যাপ ইনস্টল করতে দেয় না। MXFF Play Store listing খুলতে পারে; ইনস্টল ব্যবহারকারী করবেন।
6. **সকল অ্যাপে সমানভাবে কাজ:** প্রতিটি অ্যাপের UI, Android version, permission এবং accessibility সীমাবদ্ধতার কারণে ১০০% universal control সম্ভব নয়।

## নিরাপত্তা ও গোপনীয়তা

- অন্য অ্যাপের স্ক্রিন/notification-এ ব্যক্তিগত তথ্য থাকতে পারে। Screen capture চালুর আগে Android-এর নিজস্ব MediaProjection consent dialog দেখাতে হবে।
- মেসেজ পাঠানো, কল করা, কেনাকাটা বা অ্যাকাউন্ট-সম্পর্কিত কাজের আগে MXFF-কে কাজটি পড়ে শুনিয়ে স্পষ্ট সম্মতি নিতে হবে।
- Notification Listener-এর ডেটা ডিভাইসেই রাখা উচিত; ব্যবহারকারী স্পষ্টভাবে না চাইলে সার্ভারে পাঠানো যাবে না।
- Wake-word listener চললে স্থায়ী foreground notification, দৃশ্যমান বন্ধ করার ব্যবস্থা এবং emergency stop থাকা উচিত।
- কোনো Accessibility Service যোগ করলে সেটি শুধু ব্যবহারকারীর স্পষ্টভাবে চালু করা অবস্থায় চলবে; পাসওয়ার্ড/OTP/ব্যাংকিং তথ্য সংগ্রহ করা যাবে না।

## বিল্ড ও সেটআপ

1. Android Studio-তে `android/` ফোল্ডার খুলুন। JDK 17 এবং Android SDK Platform 34 ইনস্টল করুন।
2. `android/app/build.gradle.kts`-এ `WEB_URL`-কে আপনার প্রকাশিত MXFF web app URL-এর সঙ্গে মিলিয়ে নিন।
3. Web app-এর `/api/mxff-ask` endpoint deploy/republish না থাকলে floating assistant AI উত্তর পাবে না।
4. Gradle Sync-এর পর Build → Build APK(s) চালান।
5. ফোনে ইনস্টল করে প্রথমে Microphone, Display over other apps, Notification Access এবং প্রয়োজন অনুযায়ী SMS/Call permission দিন।
6. Floating Assistant চালু করে Home চাপুন; “Hey MXFF” বলুন। ফোনভেদে ব্যাটারি অপ্টিমাইজেশন থেকে অ্যাপকে unrestricted করতে হতে পারে।

## এই প্যাকেজের যাচাইয়ের অবস্থা

- সোর্স ফাইল ও Android/Web কাঠামো পর্যালোচনা করা হয়েছে।
- এই পরিবেশে Android SDK/ANDROID_HOME নেই; তাই APK assemble বা ফোনে runtime পরীক্ষা করা সম্ভব হয়নি।
- এই ZIP হলো পর্যালোচিত সোর্স ও এই ফিচার অডিট নথি; এটি নতুন করে build করা বা সব চাওয়া automation-সহ সম্পূর্ণ APK নয়।
