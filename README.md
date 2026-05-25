# 📹 LoopCam

Aplicativo Android de **gravação contínua em loop** com gerenciamento automático de armazenamento.

## ✨ Funcionalidades

- **Gravação em loop de 1 hora** — Cada segmento tem exatamente 1 hora; ao finalizar, já começa o próximo automaticamente
- **Melhor qualidade disponível** — Tenta 4K (UHD) → 1080p (FHD) → 720p (HD) → SD, usando sempre o máximo suportado pelo dispositivo
- **Câmera traseira** com áudio
- **Gerenciamento automático de armazenamento** — Apaga os arquivos mais antigos quando o espaço livre cair abaixo de 500MB
- **Serviço em background** — Continua gravando mesmo com o app fechado
- **Reinício automático** — Retoma a gravação após reinicialização do celular
- **Interface minimalista** com timer ao vivo e informações de armazenamento

## 📦 Baixar o APK

Vá até a aba **[Actions](../../actions)** do repositório → clique no build mais recente → baixe o artefato **LoopCam-release**.

Ou na aba **[Releases](../../releases)** se houver uma tag publicada.

## 🚀 Como usar

1. Instale o APK (ative "Fontes desconhecidas" nas configurações)
2. Abra o app e conceda as permissões:
   - Câmera
   - Microfone
   - Armazenamento (Android ≤ 12)
3. Toque em **INICIAR GRAVAÇÃO**
4. O app grava em background — você pode fechar a tela normalmente

### Onde ficam os vídeos?

```
Armazenamento externo > Android > data > com.loopcam > files > LoopCam > VID_YYYY-MM-DD_HH-mm-ss.mp4
```

## 🔨 Compilar o projeto

### Via GitHub Actions (recomendado)

1. Faça um fork deste repositório
2. Qualquer push na branch `main` dispara o build automaticamente
3. Baixe o APK nos artefatos do workflow

### Localmente

```bash
# Requisitos: Java 17, Android SDK (ANDROID_HOME configurado)
git clone <seu-repo>
cd LoopCam
chmod +x gradlew
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 📋 Permissões necessárias

| Permissão | Motivo |
|-----------|--------|
| `CAMERA` | Gravar vídeo |
| `RECORD_AUDIO` | Gravar áudio junto com o vídeo |
| `FOREGROUND_SERVICE` | Manter gravação em background |
| `RECEIVE_BOOT_COMPLETED` | Retomar após reinicialização |
| `WRITE_EXTERNAL_STORAGE` | Salvar vídeos (Android ≤ 9) |

## ⚙️ Configurações técnicas

| Parâmetro | Valor |
|-----------|-------|
| Duração de cada segmento | 60 minutos |
| Espaço mínimo livre mantido | 500 MB |
| Intervalo de verificação do armazenamento | 30 segundos |
| Qualidade máxima tentada | 4K UHD |
| Câmera | Traseira |

## 📱 Requisitos mínimos

- Android 7.0 (API 24) ou superior
- Câmera traseira
- Espaço suficiente para pelo menos 1 arquivo de vídeo (~4-8 GB para 1h em 4K)
