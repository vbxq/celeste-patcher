<div align="center">

  <img src="Images/kettu_logo.png" alt="Kettu logo" width="200px" style="border-radius: 50%" />
  
  # Kettu Manager

  Easily install Kettu on Android

  [![Latest release](https://img.shields.io/github/v/release/C0C0B01/KettuManager?color=3AB8BA&display_name=release&label=Latest&style=for-the-badge)](https://github.com/C0C0B01/KettuManager/releases/latest)
  
  ---

  <br>
  
  ![Downloads (latest)](https://img.shields.io/github/downloads/C0C0B01/KettuManager/latest/total?style=for-the-badge&logo=github&label=Downloads%20(Latest)&color=blue)
  ![Total downloads](https://img.shields.io/github/downloads/C0C0B01/KettuManager/total?style=for-the-badge&logo=github&label=Downloads%20(Total)&color=blue)
  ![GitHub top language](https://img.shields.io/github/languages/top/C0C0B01/KettuManager?style=for-the-badge)
    [![Stars](https://img.shields.io/github/stars/C0C0B01/KettuManager?logo=github&style=for-the-badge)](https://github.com/C0C0B01/KettuManager/stargazers)

  <br>
  
</div>

Build
---

#### Prerequisites
  - [Git](https://git-scm.com/downloads)
  - [JDK 17](https://www.oracle.com/java/technologies/javase/jdk11-archive-downloads.html)
  - [Android SDK](https://developer.android.com/studio)

#### Instructions

1. Clone the repo
    - `git clone https://github.com/C0C0B01/KettuManager.git && cd KettuManager`
2. Build the project
    - Linux: `chmod +x ./gradlew && gradlew assembleDebug`
    - Windows: `./gradlew assembleDebug`
3. Install on device
    - [Enable USB debugging](https://developer.android.com/studio/debug/dev-options) and plug in your phone
    - Run `adb install app/build/outputs/apk/debug/app-debug.apk`

## Contributing

This is an open-source project, you can do so without any programming.

Here are a few things you can do:

- [Test and report issues](https://github.com/C0C0B01/KettuManager/issues/new/choose)
    
License
---
KettuManager is licensed under the Open Software License version 3.0

[![License: OSL v3](https://img.shields.io/badge/License-OSL%20v3-blue.svg?style=for-the-badge)](https://github.com/C0C0B01/KettuManager/blob/main/LICENSE)
