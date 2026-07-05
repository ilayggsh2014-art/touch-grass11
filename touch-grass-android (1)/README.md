# Touch Grass — APK Build

## מה יש כאן

פרויקט Android מוכן לבנייה. האפליקציה כבר מובנית בפנים (`android/app/src/main/assets/public/`).
GitHub Actions רק מריץ `gradle build` — לא צריך npm או Node.js.

## שלבים לקבלת APK

### 1. צור ריפו ב-GitHub
לך ל-[github.com/new](https://github.com/new) וצור ריפו חדש (ציבורי או פרטי).

### 2. דחוף את הקוד
```bash
git init
git add .
git commit -m "Touch Grass Android"
git branch -M main
git remote add origin https://github.com/YOUR_USER/YOUR_REPO.git
git push -u origin main
```

### 3. GitHub Actions בונה אוטומטית
הדחיפה מפעילה את ה-workflow. אחרי ~10 דקות:
- לך ל-**Actions** בריפו
- לחץ על הריצה האחרונה
- גלול ל-**Artifacts** > הורד `touch-grass-debug`
- חלץ — תמצא `app-debug.apk`

### 4. התקן על הטלפון
1. העבר את ה-APK לטלפון
2. פתח אותו
3. אפשר "Install from unknown sources"
4. התקן, פתח, אשר הרשאות

## הרשאות שהאפליקציה מבקשת
- מיקום (כדי לדעת אם אתה בחוץ)
- פעילות גופנית (לספירת צעדים)
- התראות (תזכורות לצאת החוצה)
- מיקום רקע (לעדכון אוטומטי בזמן שהמסך סגור)
