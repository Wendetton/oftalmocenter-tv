# OftalmoCenterTV

App Android para exibir o sistema de chamada de pacientes no FireTV.

## Configuração obrigatória antes de buildar

Edite o arquivo `app/src/main/java/com/oftalmocenter/tv/MainActivity.kt` e substitua a URL:

```kotlin
private const val TV_URL = "https://SEU-PROJETO.vercel.app/tv"
```

Coloque a URL real do seu sistema, por exemplo:
```kotlin
private const val TV_URL = "https://webtv-oftalmocenter.vercel.app/tv"
```

## Como gerar o APK

1. Faça um commit no branch `main`
2. O GitHub Actions vai gerar o APK automaticamente
3. Acesse **Actions → Build APK → Artifacts** para baixar
4. Ou acesse **Releases** — o APK aparece automaticamente

## Como instalar no FireTV

1. No FireTV, vá em **Settings → My Fire TV → Developer Options → Install Unknown Apps** → ative para o **Downloader**
2. Instale o app **Downloader** pela Amazon App Store
3. Abra o Downloader e cole o link do APK do GitHub Releases
4. Instale e abra o **OftalmoCenterTV**

## Funcionalidades

- Abre `/tv` em fullscreen real (sem barra de navegação)
- Inicia automaticamente quando o FireTV liga
- Tecla "Voltar" recarrega a página (não sai do app)
- Suporte completo a YouTube embed, DRM e áudio
- Reconecta automaticamente em caso de erro de rede
