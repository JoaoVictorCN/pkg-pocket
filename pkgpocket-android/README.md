# PKG Pocket (v0.1 MVP)

Android app para enviar arquivos PKG que o próprio usuário possui para um PS4 usando o Remote Package Installer (RPI), sem Termux.

## Já automatizado

- Seleção múltipla de PKGs pelo seletor do Android.
- Leitura local do header do PKG, `param.sfo` (entry `0x1000`) e `icon0.png` (`0x1200`).
- Nome, Title ID/CUSA, Content ID, versão e categoria.
- Classificação Game (`gd`), Update (`gp`) e DLC (`ac`/`al`).
- Ordenação automática: jogo → update → DLC.
- Detecção do RPI na rede local pela porta 12800.
- Servidor HTTP interno no Android (porta 8080), com `HEAD`, `GET` e `Range: bytes`/HTTP 206 para arquivos grandes.
- Fila de instalação via `/api/install`.
- Progresso via `/api/get_task_progress`.
- Foreground service + wake lock para reduzir interrupções durante transferências.
- GitHub Actions que gera um APK debug automaticamente.

## Uso

1. No PS4, abra o Remote Package Installer.
2. No Android, abra PKG Pocket e selecione um ou mais `.pkg`.
3. Toque em **Detectar** ou informe o IP do PS4.
4. Toque em **Instalar tudo**.

O celular e o PS4 devem estar na mesma LAN. Não inclua PKGs, jogos ou conteúdo comercial no repositório do app.

## Observações da v0.1

- A descoberta automática assume uma LAN IPv4 `/24`, que cobre a maioria dos roteadores domésticos.
- A leitura de `param.sfo`/`icon0.png` depende de essas entradas estarem acessíveis no PKG.
- O app ainda não consulta banco de capas online; usa primeiro a arte embutida no PKG.
- Antes de distribuição comercial, teste com base games, updates, DLCs, PKGs muito grandes e diferentes firmwares/exploits.
