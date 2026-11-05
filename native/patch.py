# מוסיף לפרויקט האנדרואיד: ווידג'ט, התראות וסנכרון ברקע
import glob, os, re, shutil
src = 'android/app/src/main'
java = src + '/java/com/betzalel/carcalendar'
html = open('index.html', encoding='utf-8').read()
url = re.search(r"SUPABASE_URL = '([^']*)'", html).group(1)
key = re.search(r"SUPABASE_KEY = '([^']*)'", html).group(1)
os.makedirs(java, exist_ok=True)
for f in glob.glob('native/java/*.java'):
    t = open(f, encoding='utf-8').read().replace('@@URL@@', url).replace('@@KEY@@', key)
    open(os.path.join(java, os.path.basename(f)), 'w', encoding='utf-8').write(t)
for d in ('layout', 'xml', 'drawable'):
    os.makedirs(f'{src}/res/{d}', exist_ok=True)
    for f in glob.glob(f'native/res/{d}/*'):
        shutil.copy(f, f'{src}/res/{d}/')
m = open(src + '/AndroidManifest.xml', encoding='utf-8').read()
if 'POST_NOTIFICATIONS' not in m:
    m = m.replace('<application', '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <application', 1)
if 'CarWidget' not in m:
    m = m.replace('</application>', '''    <receiver android:name=".CarWidget" android:exported="false" android:label="יומן הרכב">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/car_widget_info" />
        </receiver>
    </application>''', 1)
open(src + '/AndroidManifest.xml', 'w', encoding='utf-8').write(m)
g = 'android/app/build.gradle'
t = open(g, encoding='utf-8').read()
if 'work-runtime' not in t:
    t = t.replace('dependencies {', 'dependencies {\n    implementation "androidx.work:work-runtime:2.9.1"', 1)
open(g, 'w', encoding='utf-8').write(t)
print('native patch OK')
