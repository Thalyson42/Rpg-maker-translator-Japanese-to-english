# RPG Maker Translator

App Android que traduz **jogos japoneses de RPG Maker para inglês** direto no celular — diálogos, escolhas, nomes,
itens, habilidades, menus e termos do sistema — sem quebrar o jogo.

Idioma: [English](README.md) · **Português**

## O que faz e para que serve
Muitos jogos de RPG Maker japoneses não têm versão em inglês. O app lê os arquivos de dados do jogo, traduz os textos
e grava uma cópia traduzida, sem alterar os originais. Depois você copia os arquivos traduzidos para o jogo (ou uma
cópia dele) e joga em inglês.

**Engines suportadas**

| Engine | Arquivos | Situação |
|---|---|---|
| RPG Maker MV / MZ | `data/*.json` | suportado |
| RPG Maker VX Ace | `.rvdata2` (inclusive dentro de `.rgss3a`) | suportado |
| RPG Maker VX | `.rvdata` (inclusive dentro de `.rgss2a`) | suportado |
| RPG Maker XP | `.rxdata` (inclusive dentro de `.rgssad`) | suportado |
| RPG Maker 2000 / 2003 | `.ldb` / `.lmu` | ainda não |
| Wolf RPG Editor | `.wolf` / `.dat` | ainda não |

**Recursos**
- Três tradutores: **ML Kit** (grátis, offline após baixar um modelo de ~30 MB uma vez, sem chave), **DeepL** e
  **Google Cloud** (precisam de chave). Se a cota acabar, o app troca de tradutor ou salva um resultado parcial para
  você continuar depois.
- **Protege o jogo:** códigos de controle (`\C[2]`, `\V[1]`, `\N[1]`, `%1`...) são preservados, e uma tradução que os
  altere é descartada. Switches, variáveis, nomes de imagem/áudio, scripts, comentários e comandos de plugin nunca
  são alterados.
- **Texto cabe nas janelas:** diálogos são requebrados para o inglês (largura calculada pela tela e fonte do jogo),
  descrições de itens/habilidades cabem nas 2 linhas da janela de ajuda e mensagens longas de batalha diminuem a fonte
  em vez de serem cortadas.
- **Compatível com plugins:** nomes que plugins citam em tags de nota (ex.: `<コマンド:スキル/魔術,...>`) são
  traduzidos uma vez e a mesma tradução vai para as tags, então opções como comandos do menu de batalha não somem.
  Alguns plugins apagam todos os espaços das tags antes de comparar, o que faria nomes com espaço (ex.: "Martial arts")
  deixarem de bater. Para plugins que o app conhece (hoje o **Keke_VariableActorCommand v1.2.7**) ele grava uma
  **cópia** corrigida do plugin em `js_en/plugins/`, e os nomes mantêm os espaços — você precisa copiar esse arquivo por
  cima do original. Para qualquer outro jogo/plugin os nomes são escritos **sem espaços** (ex.: `MartialArts`) e nomes
  com dígitos ficam em japonês, o que é seguro para qualquer plugin.
- **Textos de plugins no menu:** se você escolher a pasta raiz do jogo, os parâmetros de texto do `js/plugins.js`
  (por exemplo botões extras do menu de batalha, como log ou troca de equipamento) também são traduzidos, assim como
  rótulos de botão escritos dentro de tags de nota. Tudo que um plugin compara ou referencia (identificadores, nomes de
  eventos, nomes de arquivo, scripts, números, switches) é ignorado.
- **Cache** de tradução compartilhado entre os tradutores: o mesmo texto nunca é traduzido (nem cobrado) duas vezes.

## Instalar e atualizar
1. Crie um repositório no GitHub e envie o `RpgTranslator.zip` (Add file → Upload files).
2. Add file → Create new file, com o nome `.github/workflows/build.yml`, cole o conteúdo do `build.yml` e faça o commit.
3. Abra a aba **Actions** e aguarde o build (3 a 5 minutos).
4. Baixe o `RPG-Maker-Translator.apk` em **Releases** (ou Actions → Artifacts) e instale.

**Atualização:** todo build é assinado com a mesma chave e tem número de versão maior, então o novo APK instala
**por cima** do antigo, sem desinstalar e mantendo suas configurações. (Só se você instalou antes uma versão assinada
com outra chave é preciso desinstalar uma vez.) A versão atual aparece abaixo do título do app.

> A chave de assinatura (`app/rpgtl.jks`) está no projeto para as atualizações funcionarem direto. Se for publicar o
> repositório, crie sua própria chave e troque as senhas em `app/build.gradle.kts`.

## Como usar
1. **Faça backup do jogo.** Trabalhe em uma cópia.
2. Abra o app e toque em **1. Choose game folder**. Prefira a **pasta raiz** do jogo (o app acha `data`,
   `www/data`, `Data` ou um arquivo `.rgss3a` sozinho e ainda traduz os textos de plugins do `js/plugins.js`).
   Escolher só a pasta `data` funciona, mas o texto de menu criado por plugins fica em japonês.
3. Toque em **2. Choose output folder**. O app cria uma pasta `data_en` ali.
4. Escolha o **tradutor principal**. O ML Kit não precisa de configuração (precisa de internet na primeira vez, para
   baixar o modelo). Para DeepL ou Google, cole a chave. Deixe marcada a opção de continuar com os outros tradutores se
   quiser troca automática.
5. Deixe marcada a opção **"Also translate plugin menu text"** para traduzir parâmetros de plugin (precisa da pasta
   raiz). Só parâmetros de texto de tela são traduzidos; identificadores, nomes de meta-tags, nomes de listas/modelos,
   nomes de eventos e tudo que o código do plugin compara são mantidos. O log mostra quantos textos foram traduzidos
   por plugin. Desmarque se algum plugin se comportar mal depois da tradução.
   Ajuste **Characters per dialogue line** (padrão 50). O app nunca passa do que cabe na tela do jogo.
6. Toque em **3. Translate** e mantenha a tela ligada até terminar. O log mostra o progresso e os avisos.
7. Quando aparecer **Translation complete**, copie o conteúdo de `data_en` por cima da pasta de dados do jogo (mesmos
   nomes). Se o app também criou a pasta `js_en`, copie o conteúdo dela por cima da pasta `js` do jogo (`js_en/plugins.js`
   por cima de `js/plugins.js`, e cada plugin corrigido em `js_en/plugins/...` por cima do mesmo caminho). Guarde os
   originais. **Não pule o plugin corrigido:** sem ele, os comandos de batalha citados em tags não aparecem. Em jogos empacotados em `.rgss3a`/`.rgss2a`/`.rgssad`, crie uma pasta `Data` ao lado do `Game.exe`, coloque
   os arquivos lá e guarde o pacote como backup (se o jogo continuar em japonês, tire o pacote do lugar).
8. Abra o jogo.

**Cache:** durante a tradução os textos prontos são salvos, para você poder parar e continuar. Quando a tradução
**termina por completo**, o cache é limpo automaticamente. Se parar antes (cota, cancelamento), o cache é mantido para
você continuar. O botão **Clear translation cache** apaga manualmente.

## Limitações
- Texto dentro de imagens **não** é traduzido.
- Texto japonês escrito direto no código de um plugin (fora dos parâmetros) não é traduzido; o log lista os plugins que
  o contêm, e é preciso editar à mão.
- Palavras japonesas em tags de nota que não são rótulos de botão são mantidas (o log mostra exemplos).
- Nomes muito longos (por exemplo tipos de habilidade na janela estreita de comandos de batalha) ainda podem ser
  cortados; o log avisa.
- Tradução automática pode errar. O DeepL costuma dar o melhor resultado.
- RPG Maker 2000/2003 e Wolf RPG ainda não são suportados.

## Problemas comuns
- **Uma opção sumiu de um menu:** provavelmente um plugin a identifica pelo nome em japonês. Veja no log
  "Names referenced by note tags" e "Warning: note tags contain Japanese text" e, se preciso, envie o arquivo do
  plugin para análise.
- **Um botão do menu continua em japonês:** provavelmente é um parâmetro de plugin (escolha a pasta raiz do jogo e
  copie `js_en/plugins.js` para `js/`) ou um texto escrito no código de um plugin (veja no log o aviso sobre plugins
  com japonês no código).
- **Texto cortado:** diminua *Characters per dialogue line* ou traduza de novo.
- **"Stopped by a quota limit":** rode de novo mais tarde ou troque de tradutor; o cache guarda o que já foi traduzido.
