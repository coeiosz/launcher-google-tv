# Projeto TV

Launcher leve no estilo Apple TV e scripts de manutenção para uma **Chromecast com Google TV 4K** (Android 14, 2 GB de RAM).

![Tela inicial](docs/inicio.png)

## O que tem aqui

| Pasta | Conteúdo |
|---|---|
| `tvhome/` | Launcher `dev.tvhome`: Java puro, sem bibliotecas, APK de ~30 KB |
| `tv-setup/` | Debloat aplicado na TV e script para desfazer |
| `docs/` | Capturas de tela |

## Launcher

- Grade de banners com foco ampliado, sombra e reflexo; painel com glow na cor do app em foco.
- Fundo desfocado e com vinheta, derivado do app em foco (escurecido para ícones claros).
- Relógio, data e atalhos: Wi-Fi (com status), Parear controle, Imagem e som, Modo ambiente.
- Reordenar apps: segure OK, mova com as setas, OK para soltar.
- ~22–45 MB de RAM (o launcher do Google usa ~99 MB), frame médio de 11–14 ms.

### Compilar e instalar

Requer JDK 17+, Android SDK (`platforms;android-34`, `build-tools;36.0.0`) e Platform Tools.

```powershell
# uma vez: senha da chave de assinatura (não versionada)
Set-Content tvhome\signing.local "sua-senha" -NoNewline
.\tvhome\build.ps1 -Install -Tv 192.168.0.126:PORTA
```

Na primeira compilação a chave `tvhome.keystore` é criada. **Guarde uma cópia dela e da senha fora do repositório**: sem ela, atualizar o launcher exige desinstalar antes.

Permissões concedidas via ADB após instalar:

```powershell
adb shell appops set dev.tvhome WRITE_SETTINGS allow          # atalho Modo ambiente
adb shell pm grant dev.tvhome android.permission.BLUETOOTH_CONNECT   # atalho Parear controle
```

### Tornar tela inicial (e desfazer)

```powershell
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity dev.tvhome/.HomeActivity
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

Para voltar à tela do Google: `tvhome\revert-home.ps1`.

> Desativar só o `launcherx` faz a TV entrar em loop na tela de recuperação do Google (tela preta). Sempre desative o `setupwraith` antes.

## Scripts de apoio

| Script | Função |
|---|---|
| `tvhome/tv_remote.py` | Controle remoto pelo Wi-Fi (protocolo Android TV Remote v2), útil sem controle físico |
| `tvhome/bench.ps1` | Mede tempo de abertura, frames e memória na TV |
| `tvhome/rescue.ps1` | Espera a depuração voltar e reativa a tela do Google |
| `tvhome/revert-home.ps1` | Restaura a tela inicial do Google |
| `tv-setup/restaurar-tv.ps1` | Reativa os apps desativados no debloat |
