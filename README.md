
**Drone Sound Alert** is an advanced Android-based monitoring tool designed for hobbyist use to acoustically detect the presence of drones and monitor the security of the GPS environment (jamming detection). It is optimized for use in quiet environments outside urban areas to provide high-precision situational awareness.

## 🚀 Key Features

### Acoustic Drone Detection
*   **AI-Based Analysis:** Utilizes TensorFlow Lite models to identify drone motor sounds from background noise.
*   **Spectral Analysis (FFT):** Provides real-time frequency analysis searching for narrow-band frequency peaks typical of drones (2kHz – 8kHz).
*   **Background Monitoring:** Operates as a continuous Android Foreground Service, allowing monitoring even when the screen is locked.
*   **Smart Filtering:** Adjustable consecutive detection counts (3–15 seconds) to minimize false alarms.

### GNSS (GPS) Jamming Detection
*   **Multi-Indicator Scoring:** Analyzes satellite signal quality (C/N0), sudden delta drops, loss of lock, and multi-constellation suppression to detect active jamming.
*   **Dynamic Baseline Mapping:** Learns the optimal signal level of your current location (10–60s) to adapt to different environments.
*   **Visual Analysis:** Real-time charts for signal level history and interference scores.

### Multi-Channel Alarms
*   **Local Alerts:** Selected alarm sounds, vibration, and camera LED strobe.
*   **Remote Alerts:** Automated SMS with Google Maps links, Email (SMTP/SendGrid), and Rsyslog (UDP/TLS).
*   **External Integration:** Send trigger strings to paired Bluetooth devices (e.g., external sirens).

## 🛠 Technical Specifications
*   **Platform:** Android (Min SDK 26, Target SDK 34).
*   **Architecture:** High-priority Foreground Service with Microphone and Location capabilities.
*   **Libraries:** TensorFlow Lite, JTransforms, MPAndroidChart, Retrofit, JavaMail.

## ⚖️ Legal Disclaimer
**Hobbyist Use Only:** This application is intended for hobbyist use in quiet environments outside urban areas. It is provided "as is", without warranty of any kind, express or implied.
**No Liability:** The developer is not responsible for any security failures, undetected drones, or interference caused by GNSS jamming. 
**Compliance:** Users are responsible for complying with all local aviation, privacy, and radio frequency laws.

## 📄 License
This project is licensed under the **MIT License** 

## 📚 Third-Party Attributions
This app uses several open-source libraries:
*   [TensorFlow Lite](https://www.tensorflow.org/lite) (Apache 2.0)
*   [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) (Apache 2.0)
*   [JTransforms](https://github.com/wendykierp/JTransforms) (BSD-2-Clause)
*   [Retrofit](https://square.github.io/retrofit/) (Apache 2.0)
*   [Android-Mail](https://github.com/javaee/javamail) (CDDL/GPLv2)
