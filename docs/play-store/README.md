# Google Play store listing

The text and graphics of TreasuredWord's default (en-US) store listing in Play Console
(**Grow users → Store presence → Store listings → Default store listing**). Change them here and
in Play Console together, so the repo matches what users see.

## Text

The title carries "Bible" so the app shows up in Bible searches (#338): on Play the title is the
strongest search signal. Play's metadata policy allows a plain description like this, but not
"best", "#1", emojis or all-caps words, and "free" only outside the title, icon and developer
name. The full description writes the name once as two words, "TreasuredWord (Treasured Word)",
for people who search for it that way.

The texts describe 1.9 (#390): Memorize with its practice games, chapter audio, the licensed
Bibles (LBLA, NBLA, NASB, AMP) with word search, search by meaning with key passages,
BibleProject's chapter videos and the Bible Helper's improvements. Change them only together with
the release that changes what they promise, so the listing never describes features the installed
app lacks.

- Licensed versions are named only as versions, with "used by permission of The Lockman
  Foundation", and YouVersion is never named (`docs/bible-licensing.md`). The ESV is left out
  while it's switched off (#377). The audio is the Berean Standard Bible in English only, so
  every language says so.
- Memorize sits under STUDY rather than a new all-caps heading.

**App name** (30 max): TreasuredWord: Bible App

**Short description** (80 max):

> Read the Bible offline, memorize verses, take notes and ask the Bible Helper.

**Full description** (4000 max):

```text
TreasuredWord (Treasured Word) is a free Bible reading app with no ads and no tracking.

READ
• Download free Bible translations in many languages and read them offline.
• Also the NASB 2020, NASB 1995 and Amplified Bible (AMP) in English, and La Biblia de las Américas (LBLA) and Nueva Biblia de las Américas (NBLA) in Spanish. Each chapter needs an internet connection the first time you open it, then stays on your phone.
• Listen to the Berean Standard Bible (BSB) read aloud in English, chapter after chapter, with two narrators and the speed you choose. It keeps playing with the screen off.
• Pick up where you left off, and keep a bookmark in your place.
• Search the Bible by word, in any version, including the ones above.
• A verse of the day, with an optional daily reminder and a home screen widget.

STUDY
• Highlight verses in five colors.
• Write notes on one verse or several.
• Search by meaning finds verses about what you searched, even when they use other words, and key passages for many topics.
• Memorize: save verses to learn by heart and practice them with seven short games, such as Fill the gaps, Put it in order and Type it from memory.
• Each verse comes back for review after 1, 2, 4, 7, 14 and 30 days, until you know it by heart. A home screen widget shows a verse to memorize each day.
• "About this book" links each book to its GotQuestions.org summary and its BibleProject overview video. Home and "About this book" also show BibleProject's videos for the chapter you're reading.

ASK
• The Bible Helper answers questions about the Bible and the Christian faith, in the Ask tab. Each answer cites the verses it rests on, and shows them in your translation.
• Answers appear as they're written, and the starter questions are answered right away.
• When a search sounds like something to talk through, Search offers to ask the Bible Helper.
• It answers in line with the Baptist Faith and Message 2000.
• The Bible Helper is an AI and can be wrong. Check what it says against Scripture.
• It needs a free account and an internet connection, and there's a daily limit of questions. You can report an answer from inside the app.

SHARE
• Share a verse as an image, in the style and size you choose, or on your own photo.
• Save it to your photos or set it as your wallpaper, with an optional new verse wallpaper every day.

YOURS
• No account needed to read, highlight, write notes and memorize: without one, everything you create stays on your phone.
• Or create a free account to sync your highlights, notes, Memorize list and reading position between devices. You can delete it at any time from inside the app.
• Six color themes, each in light and dark, adjustable text size, and a layout that works in landscape.
• Available in English, Spanish, Portuguese, French, German, Indonesian, Filipino, Swahili, Korean and Simplified Chinese.

Bible translations come from bible.helloao.org; the NASB, AMP, LBLA and NBLA are used by permission of The Lockman Foundation. The verse of the day comes from OurManna. BibleProject videos are by BibleProject, their author and owner. GotQuestions.org and BibleProject content opens on their own sites and apps; TreasuredWord isn't affiliated with either.
```

## Translated listings

Play Console → Grow users → Store presence → Store listings → Default store listing →
**Manage translations → Add your own translations**, one per language the app is translated into.
Someone searching "Biblia" or "Alkitab" only finds a listing in their language, so each title and
short description has "Bible" in that language. Feature names match the app's own strings
(`ai_helper_title`, `title_ask`, `about_book_open`, `verse_of_the_day`, `label_memorize`,
`practice_game_*`, `search_section_key_passages`, `title_home`, `title_search`). The graphics are
shared with the default listing.

First sent for review on 2026-11-26 with the new title and icon (#338, #368). The 1.9 short and
full descriptions, in every language, went in with the 1.9 production release on 2026-11-28
(#390). Every short description is within 80 characters and every full description within 4,000.

### German (de-DE)

**App name:** TreasuredWord: Bibel-App

**Short description:**

> Lies die Bibel offline, lerne Verse auswendig und frag den Bibelhelfer.

<details><summary>Full description</summary>

```text
TreasuredWord (Treasured Word) ist eine kostenlose Bibel-App ohne Werbung und ohne Tracking.

LESEN
• Lade kostenlose Bibelübersetzungen in vielen Sprachen herunter und lies sie offline.
• Außerdem die NASB 2020, NASB 1995 und Amplified Bible (AMP) auf Englisch sowie La Biblia de las Américas (LBLA) und Nueva Biblia de las Américas (NBLA) auf Spanisch. Jedes Kapitel braucht beim ersten Öffnen Internet und bleibt dann auf deinem Handy.
• Hör dir die Berean Standard Bible (BSB) Kapitel für Kapitel auf Englisch vorgelesen an, mit zwei Sprechern und im Tempo deiner Wahl, auch bei ausgeschaltetem Bildschirm.
• Lies dort weiter, wo du aufgehört hast, mit einem Lesezeichen an deiner Stelle.
• Durchsuche die Bibel nach Wörtern, in jeder Übersetzung, auch in den oben genannten.
• Ein Vers des Tages, mit optionaler täglicher Erinnerung und einem Widget für den Startbildschirm.

STUDIEREN
• Markiere Verse in fünf Farben.
• Schreib Notizen zu einem Vers oder zu mehreren.
• Die Suche nach Bedeutung findet Verse zu dem, was du suchst, auch wenn sie andere Wörter verwenden, und wichtige Bibelstellen zu vielen Themen.
• Merkverse: Speichere Verse, die du auswendig lernen willst, und übe sie mit sieben kurzen Spielen wie „Lücken füllen“, „Richtige Reihenfolge“ und „Aus dem Gedächtnis tippen“.
• Jeder Vers kommt nach 1, 2, 4, 7, 14 und 30 Tagen zur Wiederholung, bis du ihn auswendig kannst. Ein Widget zeigt jeden Tag einen Vers zum Einprägen.
• „Über dieses Buch“ verlinkt jedes Buch mit seiner Zusammenfassung auf GotQuestions.org und seinem Übersichtsvideo von BibleProject. „Start“ und „Über dieses Buch“ zeigen auch die Videos von BibleProject zum Kapitel, das du gerade liest.

FRAGEN
• Der Bibelhelfer beantwortet im Tab „Fragen“ Fragen zur Bibel und zum christlichen Glauben. Jede Antwort nennt die Verse, auf die sie sich stützt, und zeigt sie in deiner Übersetzung.
• Antworten erscheinen, während sie geschrieben werden, und die Startfragen werden sofort beantwortet.
• Klingt eine Suche nach etwas, worüber man reden sollte, bietet die Suche den Bibelhelfer an.
• Er antwortet im Einklang mit dem Bekenntnis „Baptist Faith and Message 2000“.
• Der Bibelhelfer ist eine KI und kann sich irren. Prüfe, was er sagt, an der Heiligen Schrift.
• Er braucht ein kostenloses Konto und eine Internetverbindung, und die Zahl der Fragen pro Tag ist begrenzt. Du kannst eine Antwort direkt in der App melden.

TEILEN
• Teile einen Vers als Bild, in Stil und Größe deiner Wahl oder auf deinem eigenen Foto.
• Speichere ihn in deinen Fotos oder mach ihn zum Hintergrundbild, auf Wunsch jeden Tag mit einem neuen Vers.

DEINS
• Zum Lesen, Markieren, für Notizen und Merkverse brauchst du kein Konto: Ohne Konto bleibt alles, was du erstellst, auf deinem Handy.
• Oder erstelle ein kostenloses Konto, um Markierungen, Notizen, Merkverse und deine Leseposition zwischen Geräten zu synchronisieren. Du kannst es jederzeit in der App löschen.
• Sechs Farbthemen, jeweils hell und dunkel, anpassbare Textgröße und ein Layout, das auch im Querformat funktioniert.
• Verfügbar auf Englisch, Spanisch, Portugiesisch, Französisch, Deutsch, Indonesisch, Filipino, Suaheli, Koreanisch und vereinfachtem Chinesisch.

Die Bibelübersetzungen stammen von bible.helloao.org; NASB, AMP, LBLA und NBLA mit Genehmigung der Lockman Foundation. Der Vers des Tages stammt von OurManna. Die Videos von BibleProject stammen von BibleProject, ihrem Urheber und Eigentümer. Inhalte von GotQuestions.org und BibleProject öffnen sich auf deren eigenen Websites und Apps; TreasuredWord ist mit keinem von beiden verbunden.
```

</details>

### Spanish (es-ES, es-419, es-US)

The same text in all three Spanish listings (Spain, Latin America, United States), so Spanish
speakers outside Spain don't get the English listing.


**App name:** TreasuredWord: Santa Biblia

**Short description:**

> Lee la Biblia sin conexión, memoriza versículos y pregunta al Ayudante bíblico.

<details><summary>Full description</summary>

```text
TreasuredWord (Treasured Word) es una app gratuita para leer la Biblia, sin anuncios y sin rastreo.

LEER
• Descarga traducciones gratuitas de la Biblia en muchos idiomas y léelas sin conexión.
• También La Biblia de las Américas (LBLA) y la Nueva Biblia de las Américas (NBLA) en español, y la NASB 2020, la NASB 1995 y la Amplified Bible (AMP) en inglés. Cada capítulo necesita conexión la primera vez que lo abres y luego se queda en tu teléfono.
• Escucha la Berean Standard Bible (BSB) leída en voz alta en inglés, capítulo tras capítulo, con dos narradores y la velocidad que elijas. Sigue sonando con la pantalla apagada.
• Sigue donde lo dejaste, con un marcador en tu lugar.
• Busca palabras en la Biblia, en cualquier versión, también en las de arriba.
• Un versículo del día, con un recordatorio diario opcional y un widget para la pantalla de inicio.

ESTUDIAR
• Resalta versículos en cinco colores.
• Escribe notas sobre un versículo o varios.
• La búsqueda por significado encuentra versículos sobre lo que buscas, aunque usen otras palabras, y pasajes clave para muchos temas.
• Memorizar: guarda versículos para aprenderlos de memoria y practícalos con siete juegos cortos, como Completa los huecos, Ponlo en orden y Escríbelo de memoria.
• Cada versículo vuelve para repasarlo a los 1, 2, 4, 7, 14 y 30 días, hasta que te lo sepas de memoria. Un widget te muestra cada día un versículo para memorizar.
• «Acerca de este libro» enlaza cada libro con su resumen en GotQuestions.org y su video de introducción de BibleProject. Inicio y «Acerca de este libro» también muestran los videos de BibleProject del capítulo que estás leyendo.

PREGUNTAR
• El Ayudante bíblico responde preguntas sobre la Biblia y la fe cristiana, en la pestaña Preguntar. Cada respuesta cita los versículos en los que se basa y los muestra en tu traducción.
• Las respuestas aparecen mientras se escriben, y las preguntas de inicio se responden al instante.
• Cuando una búsqueda suena a algo para conversar, Buscar te ofrece preguntarle al Ayudante bíblico.
• Responde de acuerdo con la Fe y Mensaje Bautista 2000.
• El Ayudante bíblico es una IA y puede equivocarse. Compara lo que dice con las Escrituras.
• Necesita una cuenta gratuita y conexión a internet, y hay un límite diario de preguntas. Puedes reportar una respuesta desde la app.

COMPARTIR
• Comparte un versículo como imagen, con el estilo y el tamaño que elijas, o sobre tu propia foto.
• Guárdalo en tus fotos o ponlo como fondo de pantalla, con un versículo nuevo cada día si quieres.

TUYO
• No necesitas cuenta para leer, resaltar, escribir notas y memorizar: sin ella, todo lo que creas se queda en tu teléfono.
• O crea una cuenta gratuita para sincronizar tus resaltados, notas, versículos para memorizar y posición de lectura entre dispositivos. Puedes eliminarla en cualquier momento desde la app.
• Seis temas de color, cada uno en claro y oscuro, tamaño de texto ajustable y un diseño que funciona en horizontal.
• Disponible en inglés, español, portugués, francés, alemán, indonesio, filipino, suajili, coreano y chino simplificado.

Las traducciones de la Biblia vienen de bible.helloao.org; la LBLA, la NBLA, la NASB y la AMP se usan con permiso de The Lockman Foundation. El versículo del día viene de OurManna. Los videos de BibleProject son de BibleProject, su autor y propietario. El contenido de GotQuestions.org y BibleProject se abre en sus propios sitios y apps; TreasuredWord no está afiliada a ninguno de los dos.
```

</details>

### French (France) (fr-FR)

**App name:** TreasuredWord : la Bible

**Short description:**

> Lisez la Bible hors ligne, mémorisez des versets et posez vos questions.

<details><summary>Full description</summary>

```text
TreasuredWord (Treasured Word) est une appli gratuite de lecture de la Bible, sans publicité et sans pistage.

LIRE
• Téléchargez des traductions gratuites de la Bible dans de nombreuses langues et lisez-les hors ligne.
• Aussi la NASB 2020, la NASB 1995 et l'Amplified Bible (AMP) en anglais, et La Biblia de las Américas (LBLA) et la Nueva Biblia de las Américas (NBLA) en espagnol. Chaque chapitre demande une connexion Internet la première fois que vous l'ouvrez, puis reste sur votre téléphone.
• Écoutez la Berean Standard Bible (BSB) lue à voix haute en anglais, chapitre après chapitre, avec deux narrateurs et la vitesse de votre choix. La lecture continue écran éteint.
• Reprenez là où vous vous étiez arrêté, avec un marque-page à votre place.
• Cherchez des mots dans la Bible, dans toutes les versions, y compris celles ci-dessus.
• Un verset du jour, avec un rappel quotidien facultatif et un widget pour l'écran d'accueil.

ÉTUDIER
• Surlignez des versets en cinq couleurs.
• Écrivez des notes sur un ou plusieurs versets.
• La recherche par le sens trouve des versets liés à ce que vous cherchez, même s'ils emploient d'autres mots, et des passages clés pour de nombreux thèmes.
• Mémoriser : gardez des versets à apprendre par cœur et entraînez-vous avec sept petits jeux, comme Texte à trous, Remettre dans l'ordre et L'écrire de mémoire.
• Chaque verset revient en révision après 1, 2, 4, 7, 14 et 30 jours, jusqu'à ce que vous le sachiez par cœur. Un widget affiche chaque jour un verset à mémoriser.
• « À propos de ce livre » relie chaque livre à son résumé sur GotQuestions.org et à sa vidéo de présentation de BibleProject. L'Accueil et « À propos de ce livre » montrent aussi les vidéos de BibleProject sur le chapitre que vous lisez.

DEMANDER
• L'Assistant biblique répond aux questions sur la Bible et la foi chrétienne, dans l'onglet Demander. Chaque réponse cite les versets sur lesquels elle s'appuie et les affiche dans votre traduction.
• Les réponses s'affichent au fil de leur rédaction, et les questions de départ obtiennent une réponse immédiate.
• Quand une recherche ressemble à un sujet dont il vaut mieux parler, Rechercher vous propose de demander à l'Assistant biblique.
• Il répond en accord avec la confession de foi baptiste « Baptist Faith and Message 2000 ».
• L'Assistant biblique est une IA et peut se tromper. Vérifiez ce qu'il dit à la lumière de l'Écriture.
• Il nécessite un compte gratuit et une connexion Internet, et le nombre de questions par jour est limité. Vous pouvez signaler une réponse depuis l'appli.

PARTAGER
• Partagez un verset en image, dans le style et la taille de votre choix, ou sur votre propre photo.
• Enregistrez-le dans vos photos ou mettez-le en fond d'écran, avec un nouveau verset chaque jour si vous le souhaitez.

À VOUS
• Aucun compte n'est nécessaire pour lire, surligner, écrire des notes et mémoriser : sans compte, tout ce que vous créez reste sur votre téléphone.
• Ou créez un compte gratuit pour synchroniser vos surlignages, vos notes, vos versets à mémoriser et votre position de lecture entre vos appareils. Vous pouvez le supprimer à tout moment depuis l'appli.
• Six thèmes de couleur, chacun en clair et en sombre, une taille de texte réglable et une mise en page adaptée au mode paysage.
• Disponible en anglais, espagnol, portugais, français, allemand, indonésien, filipino, swahili, coréen et chinois simplifié.

Les traductions de la Bible proviennent de bible.helloao.org ; la NASB, l'AMP, la LBLA et la NBLA sont utilisées avec la permission de The Lockman Foundation. Le verset du jour vient d'OurManna. Les vidéos de BibleProject sont de BibleProject, leur auteur et propriétaire. Les contenus de GotQuestions.org et de BibleProject s'ouvrent sur leurs propres sites et applis ; TreasuredWord n'est affiliée à aucun des deux.
```

</details>

### Portuguese (Brazil) (pt-BR)

**App name:** TreasuredWord: Bíblia Sagrada

**Short description:**

> Leia a Bíblia offline, memorize versículos e pergunte ao Ajudante bíblico.

<details><summary>Full description</summary>

```text
TreasuredWord (Treasured Word) é um app gratuito para ler a Bíblia, sem anúncios e sem rastreamento.

LER
• Baixe traduções gratuitas da Bíblia em vários idiomas e leia offline.
• Também a NASB 2020, a NASB 1995 e a Amplified Bible (AMP) em inglês, e La Biblia de las Américas (LBLA) e a Nueva Biblia de las Américas (NBLA) em espanhol. Cada capítulo precisa de internet na primeira vez que você o abre e depois fica no seu celular.
• Ouça a Berean Standard Bible (BSB) lida em voz alta em inglês, capítulo após capítulo, com dois narradores e a velocidade que escolher. Continua tocando com a tela desligada.
• Continue de onde parou, com um marcador no seu lugar.
• Pesquise palavras na Bíblia, em qualquer versão, incluindo as de cima.
• Um versículo do dia, com lembrete diário opcional e um widget para a tela inicial.

ESTUDAR
• Destaque versículos em cinco cores.
• Escreva anotações sobre um ou vários versículos.
• A busca por significado encontra versículos sobre o que você pesquisou, mesmo quando usam outras palavras, e passagens-chave para muitos temas.
• Memorizar: guarde versículos para aprender de cor e pratique com sete jogos curtos, como Complete as lacunas, Coloque em ordem e Escreva de memória.
• Cada versículo volta para revisão depois de 1, 2, 4, 7, 14 e 30 dias, até você saber de cor. Um widget mostra um versículo para memorizar a cada dia.
• "Sobre este livro" liga cada livro ao resumo dele no GotQuestions.org e ao vídeo de visão geral do BibleProject. O Início e "Sobre este livro" também mostram os vídeos do BibleProject sobre o capítulo que você está lendo.

PERGUNTAR
• O Ajudante bíblico responde perguntas sobre a Bíblia e a fé cristã, na aba Perguntar. Cada resposta cita os versículos em que se baseia e os mostra na sua tradução.
• As respostas aparecem enquanto são escritas, e as perguntas iniciais são respondidas na hora.
• Quando uma pesquisa parece algo para conversar, a Busca oferece perguntar ao Ajudante bíblico.
• Ele responde de acordo com a Fé e Mensagem Batista 2000.
• O Ajudante bíblico é uma IA e pode errar. Confira o que ele diz com as Escrituras.
• Ele precisa de uma conta gratuita e de conexão à internet, e há um limite diário de perguntas. Você pode reportar uma resposta pelo app.

COMPARTILHAR
• Compartilhe um versículo como imagem, no estilo e tamanho que escolher, ou na sua própria foto.
• Salve nas suas fotos ou use como papel de parede, com um versículo novo todo dia, se quiser.

SEU
• Não precisa de conta para ler, destacar, escrever anotações e memorizar: sem conta, tudo o que você cria fica no seu celular.
• Ou crie uma conta gratuita para sincronizar destaques, anotações, versículos para memorizar e a posição de leitura entre aparelhos. Você pode excluí-la a qualquer momento pelo app.
• Seis temas de cores, cada um claro e escuro, tamanho de texto ajustável e um layout que funciona na horizontal.
• Disponível em inglês, espanhol, português, francês, alemão, indonésio, filipino, suaíli, coreano e chinês simplificado.

As traduções da Bíblia vêm de bible.helloao.org; a NASB, a AMP, a LBLA e a NBLA são usadas com permissão da The Lockman Foundation. O versículo do dia vem do OurManna. Os vídeos do BibleProject são do BibleProject, seu autor e proprietário. O conteúdo do GotQuestions.org e do BibleProject abre nos sites e apps deles; o TreasuredWord não é afiliado a nenhum dos dois.
```

</details>

### Indonesian (id)

**App name:** TreasuredWord: Alkitab

**Short description:**

> Baca Alkitab offline, hafalkan ayat, buat catatan, dan tanya Asisten Alkitab.

<details><summary>Full description</summary>

```text
TreasuredWord (Treasured Word) adalah aplikasi baca Alkitab gratis, tanpa iklan dan tanpa pelacakan.

BACA
• Unduh terjemahan Alkitab gratis dalam banyak bahasa dan baca secara offline.
• Juga NASB 2020, NASB 1995, dan Amplified Bible (AMP) dalam bahasa Inggris, serta La Biblia de las Américas (LBLA) dan Nueva Biblia de las Américas (NBLA) dalam bahasa Spanyol. Setiap pasal memerlukan internet saat pertama kali dibuka, lalu tersimpan di ponsel Anda.
• Dengarkan Berean Standard Bible (BSB) dibacakan dalam bahasa Inggris, pasal demi pasal, dengan dua narator dan kecepatan pilihan Anda. Tetap diputar saat layar mati.
• Lanjutkan dari bagian terakhir yang Anda baca, dengan pembatas di tempat Anda.
• Cari kata di Alkitab, dalam versi apa pun, termasuk yang di atas.
• Ayat Hari Ini, dengan pengingat harian opsional dan widget layar utama.

PELAJARI
• Sorot ayat dengan lima warna.
• Tulis catatan untuk satu ayat atau beberapa ayat.
• Pencarian menurut makna menemukan ayat yang terkait dengan yang Anda cari, meski memakai kata lain, serta ayat-ayat kunci untuk banyak topik.
• Hafalan: simpan ayat untuk dihafal dan latih dengan tujuh permainan singkat, seperti Isi yang kosong, Susun urutannya, dan Ketik dari ingatan.
• Setiap ayat kembali untuk diulang setelah 1, 2, 4, 7, 14, dan 30 hari, sampai Anda hafal. Widget menampilkan ayat untuk dihafal setiap hari.
• "Tentang kitab ini" menautkan setiap kitab ke ringkasannya di GotQuestions.org dan video ikhtisarnya dari BibleProject. Beranda dan "Tentang kitab ini" juga menampilkan video BibleProject untuk pasal yang sedang Anda baca.

TANYA
• Asisten Alkitab menjawab pertanyaan tentang Alkitab dan iman Kristen di tab Tanya. Setiap jawaban mengutip ayat yang menjadi dasarnya dan menampilkannya dalam terjemahan Anda.
• Jawaban muncul selagi ditulis, dan pertanyaan pembuka langsung dijawab.
• Jika pencarian Anda terdengar seperti hal yang perlu dibicarakan, Cari menawarkan untuk bertanya kepada Asisten Alkitab.
• Jawabannya sejalan dengan Baptist Faith and Message 2000.
• Asisten Alkitab adalah AI dan bisa keliru. Ujilah jawabannya dengan Firman Tuhan.
• Fitur ini memerlukan akun gratis dan koneksi internet, dan ada batas pertanyaan harian. Anda dapat melaporkan jawaban langsung dari aplikasi.

BAGIKAN
• Bagikan ayat sebagai gambar, dengan gaya dan ukuran pilihan Anda, atau di atas foto Anda sendiri.
• Simpan ke galeri atau jadikan wallpaper, dengan ayat baru setiap hari jika Anda mau.

MILIK ANDA
• Tidak perlu akun untuk membaca, menyorot, menulis catatan, dan menghafal: tanpa akun, semua yang Anda buat tetap ada di ponsel Anda.
• Atau buat akun gratis untuk menyinkronkan sorotan, catatan, hafalan, dan posisi baca antarperangkat. Anda dapat menghapusnya kapan saja dari dalam aplikasi.
• Enam tema warna, masing-masing terang dan gelap, ukuran teks yang dapat diatur, dan tata letak yang berfungsi dalam mode lanskap.
• Tersedia dalam bahasa Inggris, Spanyol, Portugis, Prancis, Jerman, Indonesia, Filipino, Swahili, Korea, dan Mandarin Sederhana.

Terjemahan Alkitab berasal dari bible.helloao.org; NASB, AMP, LBLA, dan NBLA digunakan dengan izin The Lockman Foundation. Ayat Hari Ini berasal dari OurManna. Video BibleProject dibuat oleh BibleProject, pencipta dan pemiliknya. Konten GotQuestions.org dan BibleProject dibuka di situs dan aplikasi mereka sendiri; TreasuredWord tidak berafiliasi dengan keduanya.
```

</details>

### Filipino (fil)

**App name:** TreasuredWord: Bibliya

**Short description:**

> Basahin ang Bibliya offline, magsaulo ng mga talata, at magtanong tungkol dito.

<details><summary>Full description</summary>

```text
Ang TreasuredWord (Treasured Word) ay libreng app sa pagbabasa ng Bibliya, walang ads at walang tracking.

MAGBASA
• Mag-download ng mga libreng salin ng Bibliya sa maraming wika at basahin ang mga ito offline.
• Pati ang NASB 2020, NASB 1995 at Amplified Bible (AMP) sa Ingles, at ang La Biblia de las Américas (LBLA) at Nueva Biblia de las Américas (NBLA) sa Espanyol. Kailangan ng internet ang bawat kabanata sa unang pagbukas mo nito, at pagkatapos ay mananatili ito sa iyong telepono.
• Pakinggan ang Berean Standard Bible (BSB) na binabasa nang malakas sa Ingles, kabanata kada kabanata, na may dalawang tagapagbasa at bilis na gusto mo. Patuloy itong tumutugtog kahit nakapatay ang screen.
• Ituloy kung saan ka huminto, may bookmark sa iyong puwesto.
• Maghanap ng mga salita sa Bibliya, sa anumang salin, kasama ang mga nasa itaas.
• Talata ng Araw, may opsyonal na paalala araw-araw at widget sa home screen.

MAG-ARAL
• Mag-highlight ng mga talata sa limang kulay.
• Magsulat ng tala sa isa o ilang talata.
• Ang paghahanap ayon sa kahulugan ay nakakahanap ng mga talatang kaugnay ng hinanap mo, kahit iba ang mga salita, at mga pangunahing talata para sa maraming paksa.
• Isaulo: mag-save ng mga talatang gusto mong isaulo at magsanay gamit ang pitong maiikling laro, tulad ng Punan ang patlang, Ayusin ang pagkakasunod at I-type mula sa alaala.
• Bumabalik ang bawat talata para balikan pagkalipas ng 1, 2, 4, 7, 14 at 30 araw, hanggang maisaulo mo ito. May widget na nagpapakita ng talatang isasaulo araw-araw.
• Ang "Tungkol sa aklat na ito" ay nag-uugnay sa bawat aklat sa buod nito sa GotQuestions.org at sa overview video nito mula sa BibleProject. Ipinapakita rin ng Home at ng "Tungkol sa aklat na ito" ang mga video ng BibleProject para sa kabanatang binabasa mo.

MAGTANONG
• Sinasagot ng Katulong sa Bibliya ang mga tanong tungkol sa Bibliya at sa pananampalatayang Kristiyano, sa tab na Magtanong. Binabanggit ng bawat sagot ang mga talatang pinagbatayan nito at ipinapakita ang mga ito sa iyong salin.
• Lumalabas ang mga sagot habang isinusulat, at agad na nasasagot ang mga panimulang tanong.
• Kapag parang bagay na dapat pag-usapan ang hinahanap mo, iaalok ng Maghanap na magtanong sa Katulong sa Bibliya.
• Sumasagot ito ayon sa Baptist Faith and Message 2000.
• AI ang Katulong sa Bibliya at maaaring magkamali. Suriin ang sinasabi nito ayon sa Kasulatan.
• Kailangan nito ng libreng account at koneksyon sa internet, at may limitasyon sa dami ng tanong bawat araw. Maaari kang mag-ulat ng sagot mula sa loob ng app.

MAGBAHAGI
• Ibahagi ang isang talata bilang larawan, sa istilo at laking gusto mo, o sa sarili mong litrato.
• I-save ito sa iyong mga litrato o gawing wallpaper, na may bagong talata araw-araw kung gusto mo.

IYO
• Hindi kailangan ng account para magbasa, mag-highlight, magsulat ng tala at magsaulo: kung wala nito, mananatili sa iyong telepono ang lahat ng ginawa mo.
• O gumawa ng libreng account para i-sync ang iyong mga highlight, tala, talatang isasaulo at puwesto sa pagbabasa sa iba't ibang device. Maaari mo itong burahin anumang oras mula sa loob ng app.
• Anim na color theme, bawat isa ay may light at dark, naaayos na laki ng teksto, at layout na gumagana sa landscape.
• Available sa Ingles, Espanyol, Portuges, Pranses, Aleman, Indonesian, Filipino, Swahili, Koreano at Simplified Chinese.

Ang mga salin ng Bibliya ay mula sa bible.helloao.org; ginagamit ang NASB, AMP, LBLA at NBLA nang may pahintulot ng The Lockman Foundation. Ang Talata ng Araw ay mula sa OurManna. Ang mga video ng BibleProject ay gawa ng BibleProject, ang may-akda at may-ari nito. Bumubukas ang nilalaman ng GotQuestions.org at BibleProject sa sarili nilang mga site at app; hindi kaanib ang TreasuredWord sa alinman sa kanila.
```

</details>

### Swahili (sw)

**App name:** TreasuredWord: Biblia

**Short description:**

> Soma Biblia bila intaneti, kariri mistari, andika madokezo na uliza maswali.

<details><summary>Full description</summary>

```text
TreasuredWord (Treasured Word) ni programu ya bure ya kusoma Biblia, bila matangazo na bila ufuatiliaji.

SOMA
• Pakua tafsiri za bure za Biblia katika lugha nyingi na uzisome bila intaneti.
• Pia NASB 2020, NASB 1995 na Amplified Bible (AMP) kwa Kiingereza, na La Biblia de las Américas (LBLA) na Nueva Biblia de las Américas (NBLA) kwa Kihispania. Kila sura inahitaji intaneti mara ya kwanza unapoifungua, kisha inabaki kwenye simu yako.
• Sikiliza Berean Standard Bible (BSB) ikisomwa kwa sauti kwa Kiingereza, sura baada ya sura, na wasomaji wawili na kasi unayochagua. Inaendelea kucheza hata skrini ikiwa imezimwa.
• Endelea pale ulipoishia, ukiwa na alamisho mahali pako.
• Tafuta maneno katika Biblia, katika tafsiri yoyote, pamoja na zile zilizo juu.
• Mstari wa Siku, pamoja na kikumbusho cha kila siku ukipenda na wijeti ya skrini ya nyumbani.

JIFUNZE
• Weka alama kwenye mistari kwa rangi tano.
• Andika madokezo kwenye mstari mmoja au kadhaa.
• Utafutaji kwa maana unapata mistari inayohusiana na ulichotafuta, hata kama inatumia maneno mengine, na vifungu muhimu kwa mada nyingi.
• Kukariri: hifadhi mistari ya kujifunza kwa moyo na uifanyie mazoezi kwa michezo saba mifupi, kama Jaza mapengo, Panga kwa mpangilio na Andika kwa kumbukumbu.
• Kila mstari unarudi kwa marudio baada ya siku 1, 2, 4, 7, 14 na 30, hadi uukariri. Wijeti inaonyesha mstari wa kukariri kila siku.
• "Kuhusu kitabu hiki" kinaunganisha kila kitabu na muhtasari wake kwenye GotQuestions.org na video yake ya utangulizi ya BibleProject. Nyumbani na "Kuhusu kitabu hiki" pia zinaonyesha video za BibleProject za sura unayosoma.

ULIZA
• Msaidizi wa Biblia anajibu maswali kuhusu Biblia na imani ya Kikristo, kwenye kichupo cha Uliza. Kila jibu linataja mistari linayoitegemea na kuionyesha katika tafsiri yako.
• Majibu yanaonekana yanapoandikwa, na maswali ya kuanzia yanajibiwa papo hapo.
• Utafutaji wako ukionekana kuwa jambo la kuzungumzia, Tafuta inakupa nafasi ya kumuuliza Msaidizi wa Biblia.
• Anajibu kulingana na Baptist Faith and Message 2000.
• Msaidizi wa Biblia ni AI na anaweza kukosea. Pima anachosema kwa Maandiko.
• Anahitaji akaunti ya bure na muunganisho wa intaneti, na kuna kikomo cha maswali kwa siku. Unaweza kuripoti jibu ndani ya programu.

SHIRIKI
• Shiriki mstari kama picha, kwa mtindo na ukubwa unaochagua, au juu ya picha yako mwenyewe.
• Uhifadhi kwenye picha zako au uuweke kama mandhari ya simu, na mstari mpya kila siku ukipenda.

CHAKO
• Huhitaji akaunti ili kusoma, kuweka alama, kuandika madokezo na kukariri: bila akaunti, kila unachounda kinabaki kwenye simu yako.
• Au fungua akaunti ya bure ili kusawazisha alama, madokezo, mistari ya kukariri na mahali ulipofikia kusoma kati ya vifaa. Unaweza kuifuta wakati wowote ndani ya programu.
• Mandhari sita za rangi, kila moja kwa mwanga na giza, ukubwa wa maandishi unaoweza kubadilishwa, na mpangilio unaofanya kazi simu ikiwa imelazwa.
• Inapatikana kwa Kiingereza, Kihispania, Kireno, Kifaransa, Kijerumani, Kiindonesia, Kifilipino, Kiswahili, Kikorea na Kichina Kilichorahisishwa.

Tafsiri za Biblia zinatoka bible.helloao.org; NASB, AMP, LBLA na NBLA zinatumika kwa ruhusa ya The Lockman Foundation. Mstari wa Siku unatoka OurManna. Video za BibleProject ni za BibleProject, mwandishi na mmiliki wake. Maudhui ya GotQuestions.org na BibleProject hufunguka kwenye tovuti na programu zao wenyewe; TreasuredWord haihusiani na yeyote kati yao.
```

</details>

### Korean (ko-KR)

**App name:** TreasuredWord: 성경

**Short description:**

> 오프라인으로 성경을 읽고, 구절을 암송하고, 메모를 남기고, 성경 도우미에게 물어보세요.

<details><summary>Full description</summary>

```text
TreasuredWord(Treasured Word)는 광고도 추적도 없는 무료 성경 읽기 앱이에요.

읽기
• 여러 언어의 무료 성경 번역본을 내려받아 오프라인으로 읽을 수 있어요.
• 영어 NASB 2020, NASB 1995, Amplified Bible(AMP)과 스페인어 La Biblia de las Américas(LBLA), Nueva Biblia de las Américas(NBLA)도 있어요. 각 장은 처음 열 때 인터넷이 필요하고, 그 뒤에는 휴대폰에 저장돼요.
• Berean Standard Bible(BSB)을 영어 낭독으로 한 장씩 이어서 들어 보세요. 낭독자 두 명과 재생 속도를 고를 수 있고, 화면이 꺼져도 계속 재생돼요.
• 읽던 곳에서 이어 읽고, 책갈피로 위치를 표시해 두세요.
• 위의 번역본을 포함해 어떤 번역본에서든 단어로 성경을 검색하세요.
• 오늘의 말씀과 선택형 매일 알림, 홈 화면 위젯.

공부하기
• 구절을 다섯 가지 색으로 하이라이트하세요.
• 한 구절이나 여러 구절에 메모를 남기세요.
• 의미 검색은 다른 단어를 쓰더라도 검색한 내용과 관련된 구절을 찾아 주고, 여러 주제의 핵심 구절도 보여 줘요.
• 암송: 외우고 싶은 구절을 저장하고 빈칸 채우기, 순서 맞추기, 외워서 입력하기 같은 일곱 가지 짧은 게임으로 연습하세요.
• 각 구절은 외울 때까지 1, 2, 4, 7, 14, 30일 뒤에 다시 복습하게 돼요. 위젯이 매일 암송 구절을 보여 줘요.
• '이 책 소개'는 각 책을 GotQuestions.org의 요약과 BibleProject의 개관 영상으로 연결해요. 홈과 '이 책 소개'에서 지금 읽는 장의 BibleProject 영상도 볼 수 있어요.

질문하기
• 성경 도우미가 질문 탭에서 성경과 기독교 신앙에 관한 질문에 답해요. 모든 답변은 근거가 되는 구절을 인용하고, 사용 중인 번역본으로 보여 줘요.
• 답변은 작성되는 동안 바로 보이고, 시작 질문에는 즉시 답해요.
• 검색한 내용이 함께 이야기해 볼 만한 고민처럼 보이면, 검색에서 성경 도우미에게 물어보도록 안내해요.
• 침례교 신앙과 메시지 2000(Baptist Faith and Message 2000)에 맞게 답해요.
• 성경 도우미는 AI라서 틀릴 수 있어요. 말씀에 비추어 확인하세요.
• 무료 계정과 인터넷 연결이 필요하고, 하루에 할 수 있는 질문 수가 정해져 있어요. 앱 안에서 답변을 신고할 수 있어요.

공유하기
• 원하는 스타일과 크기로, 또는 내 사진 위에 구절을 이미지로 공유하세요.
• 사진에 저장하거나 배경화면으로 설정하고, 원하면 매일 새 구절로 바꿀 수 있어요.

나만의 앱
• 읽기, 하이라이트, 메모, 암송에는 계정이 필요 없어요. 계정이 없으면 만든 모든 것이 휴대폰에만 남아요.
• 또는 무료 계정을 만들어 하이라이트, 메모, 암송 구절, 읽던 위치를 기기 간에 동기화하세요. 계정은 앱 안에서 언제든지 삭제할 수 있어요.
• 각각 라이트와 다크 모드가 있는 여섯 가지 색상 테마, 글자 크기 조절, 가로 모드에서도 잘 맞는 화면.
• 영어, 스페인어, 포르투갈어, 프랑스어, 독일어, 인도네시아어, 필리핀어, 스와힐리어, 한국어, 중국어 간체 지원.

성경 번역본은 bible.helloao.org에서 가져오며, NASB, AMP, LBLA, NBLA는 The Lockman Foundation의 허락을 받아 사용해요. 오늘의 말씀은 OurManna에서 가져와요. BibleProject 영상의 저작자이자 소유자는 BibleProject예요. GotQuestions.org와 BibleProject 콘텐츠는 각자의 사이트와 앱에서 열리며, TreasuredWord는 두 곳 모두와 제휴 관계가 없어요.
```

</details>

### Chinese (Simplified) (zh-CN)

**App name:** TreasuredWord：圣经

**Short description:**

> 离线阅读圣经，背诵经文、写笔记，还能向圣经助手提问。

<details><summary>Full description</summary>

```text
TreasuredWord（Treasured Word）是一款免费的圣经阅读应用，没有广告，也不追踪你。

阅读
• 下载多种语言的免费圣经译本，离线阅读。
• 还有英文的 NASB 2020、NASB 1995 和 Amplified Bible（AMP），以及西班牙文的 La Biblia de las Américas（LBLA）和 Nueva Biblia de las Américas（NBLA）。每一章第一次打开时需要联网，之后会保存在手机上。
• 收听英文朗读的 Berean Standard Bible（BSB），一章接一章，可选两位朗读者和播放速度，关闭屏幕也会继续播放。
• 从上次停下的地方继续读，并用书签标记位置。
• 按字词搜索圣经，任何译本都可以，包括上面这些。
• 每日经文，可选每日提醒和主屏幕小部件。

学习
• 用五种颜色标记经文。
• 为一节或多节经文写笔记。
• 按意思搜索能找到与你搜索内容相关的经文，即使用词不同；还会为许多主题列出重点经文。
• 背诵：保存想背下来的经文，用七个小游戏练习，例如“填空”“排顺序”和“默写”。
• 每节经文会在 1、2、4、7、14 和 30 天后再次复习，直到你背会为止。小部件每天显示一节背诵经文。
• “关于这卷书”为每卷书链接 GotQuestions.org 上的概要和 BibleProject 的概览视频。“首页”和“关于这卷书”还会显示你正在读的那一章的 BibleProject 视频。

提问
• 圣经助手在“提问”标签页中回答关于圣经和基督教信仰的问题。每个回答都会引用所依据的经文，并用你所选的译本显示。
• 回答会边写边显示，起始问题会立即得到回答。
• 当你搜索的内容像是需要聊一聊的事时，“搜索”会建议你问问圣经助手。
• 它的回答与《浸信会信仰与信息 2000》（Baptist Faith and Message 2000）一致。
• 圣经助手是 AI，可能会出错。请用圣经来检验它所说的话。
• 需要免费账户和网络连接，每天的提问次数有限。你可以在应用内举报某个回答。

分享
• 把经文分享为图片，自选样式和尺寸，也可以用你自己的照片作背景。
• 保存到相册或设为壁纸，也可以选择每天换一节新经文。

属于你
• 阅读、标记、写笔记和背诵都无需账户：没有账户时，你创建的一切都只保存在手机上。
• 也可以创建免费账户，在设备之间同步标记、笔记、背诵经文和阅读位置。你可以随时在应用内删除账户。
• 六种颜色主题，均有浅色和深色模式，可调节字体大小，横屏也能正常使用。
• 支持英语、西班牙语、葡萄牙语、法语、德语、印度尼西亚语、菲律宾语、斯瓦希里语、韩语和简体中文。

圣经译本来自 bible.helloao.org；NASB、AMP、LBLA 和 NBLA 经 The Lockman Foundation 许可使用。每日经文来自 OurManna。BibleProject 视频的作者和所有者是 BibleProject。GotQuestions.org 和 BibleProject 的内容会在它们自己的网站和应用中打开；TreasuredWord 与两者均无关联。
```

</details>

Third-party sources are named and the listing says there's no affiliation, as `CLAUDE.md`
requires for GotQuestions.org and BibleProject. Avoid promotional words like "best" or "#1" in
the graphics; Play's metadata policy rejects them.

## Data safety

Play Console → Policy and programs → App content → **Data safety**. It must match
`docs/privacy-policy.md`: recheck it whenever the app starts sending data somewhere new.

- **Shared:** none. Cloudflare (Bible Helper, search by meaning) works as our service provider,
  and YouVersion only runs the chapter fetch or word search the user asked for, without the
  account.
- **Collected:** Personal info (name, email address, user IDs, for the account) and App activity
  (other user-generated content and other actions, for sync and the Bible Helper).
- **In-app search history** (added 2026-11-28 with 1.9, #390): collected, optional, for app
  functionality and analytics, and not processed ephemerally. Search words go to our server and
  Cloudflare (search by meaning) and to YouVersion (word search in the licensed Bibles); the server
  doesn't store them, but the reports people choose to send with "These results didn't help" are
  kept for 90 days.
- Encrypted in transit; accounts can be deleted from inside the app or through the privacy
  policy's "Deleting your account" link.

## Graphics

| Slot | File | Size |
|---|---|---|
| App icon | `../icon-source/playstore-icon-512.png` | 512×512 |
| Feature graphic | `feature-graphic.png` | 1024×500 |
| Phone screenshots | `screenshots/1-home.png` … `8-ask.png`, uploaded in that order | 1080×1920 |

Play only accepts phone screenshots in a 16:9 or 9:16 ratio, so the full-height S23 size
(1080×2340) is rejected. The screenshots were taken in October 2026 on an emulator set to
`wm size 1080x1920` with the status bar in demo mode (`sysui_demo_allowed`, clock 9:00). They
use the KJV with sample highlights and a sample note in Psalm 23. `8-ask.png` (November 2026, for
1.8) is a real Bible Helper answer to the starter "How should I pray?", taken the same way while
signed in; it's a screenshot, so it isn't labeled as AI-generated in Play's AI asset declaration. At least four screenshots of
1080 px or more on each side keep the app eligible for Play's promotions.

The feature graphic is the app icon's open Bible on its brown tile (#368), with the name in Noto
Serif Bold and the tagline in Roboto, both as letter outlines. Its source is `feature-graphic.svg`;
`python docs/icon-source/make_icons.py` copies the icon's book into it and renders it at 1024×500
as an RGB PNG, since Play rejects feature graphics with transparency. The app icon and the feature
graphic were made with AI's help, so they're declared as AI-generated in Play's AI asset
declaration (#110, #368).
