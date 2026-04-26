#!/usr/bin/env bash
# deploy.sh — build + instalação rápida no Fire TV Stick via ADB wireless.
#
# Pré-requisitos no MacBook:
#   brew install android-platform-tools
#   (Android SDK só é necessário para a Opção B / build local)
#
# Pré-requisitos no Fire TV:
#   Configurações → Meu Fire TV → Sobre → clicar 7x para virar dev
#   Ativar "Depuração ADB" e "Apps de fontes desconhecidas"
#   Anotar o IP (Configurações → Rede)
#
# Uso:
#   FIRE_TV_IP=192.168.1.105 ./deploy.sh           # build local (Opção B)
#   FIRE_TV_IP=192.168.1.105 APK=~/Downloads/app-release.apk ./deploy.sh
#       # apenas instala um APK pronto baixado do GitHub Actions (Opção A)

set -euo pipefail

PACKAGE="com.oftalmocenter.tv"
ACTIVITY="${PACKAGE}/.MainActivity"
FIRE_TV_IP="${FIRE_TV_IP:-}"
APK="${APK:-}"

if [[ -z "$FIRE_TV_IP" ]]; then
  echo "Erro: defina FIRE_TV_IP, ex: FIRE_TV_IP=192.168.1.105 ./deploy.sh"
  exit 1
fi

echo "→ Conectando ao Fire TV em $FIRE_TV_IP:5555"
adb connect "$FIRE_TV_IP:5555" >/dev/null
sleep 1
adb devices

if [[ -z "$APK" ]]; then
  echo "→ Build local (gradle assembleDebug)"
  ./gradlew assembleDebug
  APK="app/build/outputs/apk/debug/app-debug.apk"
fi

if [[ ! -f "$APK" ]]; then
  echo "Erro: APK não encontrado em $APK"
  exit 1
fi

echo "→ Instalando $APK"
adb -s "$FIRE_TV_IP:5555" install -r "$APK"

echo "→ Reiniciando app"
adb -s "$FIRE_TV_IP:5555" shell am force-stop "$PACKAGE" || true
adb -s "$FIRE_TV_IP:5555" shell am start -n "$ACTIVITY"

echo
echo "Pronto. Para ver logs do player:"
echo "  adb -s $FIRE_TV_IP:5555 logcat -v brief VideoPlayerManager:I MainActivity:I *:S"
