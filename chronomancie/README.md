# ⌛ Chronomancie

Mod Fabric pour **Minecraft 26.3**, **100 % côté serveur** : les joueurs se connectent avec un client vanilla, rien à installer.

> Le temps de ce monde est fêlé. Çà et là, des failles s'ouvrent sur d'autres époques…

## L'idée

Des **failles temporelles** s'ouvrent près des joueurs : un sablier géant qui tourne au milieu d'un tourbillon d'éclats,
avec une colonne de lumière visible de loin. Approche-toi et la faille s'éveille : des **vestiges** d'une autre époque
en surgissent, vague après vague. Survis, et elle s'effondre en libérant des **fragments temporels**.

Avec ces fragments, on forge quatre **artefacts** qui manipulent le temps :

| Artefact | Effet |
|---|---|
| ⏪ **Sablier du Retour** | Tu remontes *visiblement* ton propre trajet des 8 dernières secondes (à l'envers, en accéléré) et tu retrouves la santé que tu avais. Parfait après une chute ou un mauvais combat. |
| 🪞 **Miroir d'Écho** | Ton double — avec ton vrai skin — surgit là où tu étais il y a 10 s et **rejoue exactement tes gestes**. Les monstres s'acharnent sur lui pendant que tu t'éclipses. |
| ⏸ **Montre de Stase** | Le temps s'arrête dans une bulle de 7 blocs : flèches suspendues en plein vol, monstres pétrifiés, mèche de TNT figée. Les coups portés aux créatures figées **s'accumulent et tombent d'un seul bloc** quand le temps reprend. |
| 🔭 **Chronoscope** | Le monde se souvient des explosions. Les blocs soufflés par un creeper ou de la TNT **reviennent en volant** reprendre leur place, comme une vidéo à l'envers. Accroupi : simple aperçu en surbrillance. |

### Les époques

Chaque faille donne sur une époque, avec ses créatures, ses couleurs et ses trésors :

| Époque | Vestiges | Trésors |
|---|---|---|
| ❄ Ère Glaciaire | Vagabonds, zombies | Glace bleue, glace compactée |
| ☀ Âge des Pharaons | Zombies et squelettes du désert | Or, émeraudes |
| ✦ Ère des Illusions | Vindicateurs, pillards… et l'**Illusionniste**, le monstre oublié | Émeraudes, fioles d'expérience |
| ✧ Futur Lointain | Endermites, Vex | Perles de l'Ender, chorus, améthyste |

Autour d'une faille ouverte, le temps s'emballe : les cultures poussent à vue d'œil.

## Fabriquer les artefacts

`F` = fragment temporel (un vrai éclat d'améthyste ne marche pas).

```
Sablier du Retour     Miroir d'Écho        Montre de Stase       Chronoscope
   .  F  .             vitre F vitre         F  glace  F            .  F  .
   or ⌚ or             F   perle  F         glace ⌚ glace           F longue-vue F
   .  F  .             vitre F vitre         F  glace  F            .  F  .
```

(⌚ = horloge, glace = glace compactée, perle = perle de l'Ender.)

Chaque artefact a des **charges** (la barre de durabilité). Pour recharger : fragment dans l'autre main,
accroupi + clic droit avec l'artefact → +25 % de charges.

## Pour les joueurs

- À la première connexion, chacun reçoit le **Traité de Chronomancie**, un livre qui explique tout (`/chrono guide` pour le récupérer).
- Un onglet **Chronomancie** apparaît dans les progrès (7 progrès, dont « Le monde s'arrête » et « Maître du temps »).

## Commandes

| Commande | Qui | Effet |
|---|---|---|
| `/chrono guide` | tout le monde | Redonne le traité |
| `/chrono donner <artefact> [joueurs] [quantité]` | ops | `fragment`, `sablier`, `miroir`, `stase`, `chronoscope` |
| `/chrono faille [époque] [position]` | ops | Ouvre une faille (`glaciaire`, `pharaons`, `illusions`, `futur`) |
| `/chrono failles` | ops | Liste les failles ouvertes |
| `/chrono fermer` | ops | Referme toutes les failles |
| `/chrono recharger` | ops | Relit le fichier de réglages |

## Réglages

`config/chronomancie.json` est créé au premier lancement (durées en secondes, distances en blocs) :
fréquence des failles de jour / de nuit, nombre de vagues, durée de la stase, rayon du Chronoscope,
durée de mémoire des explosions, nombre de charges de chaque artefact… `/chrono recharger` applique les changements
sans redémarrer ; les artefacts existants s'alignent sur les nouvelles charges à leur prochaine utilisation.

## Sous le capot

| Dossier | Rôle |
|---|---|
| `temps/` | La mémoire du temps : la position de chaque joueur, tick par tick, sur 15 s |
| `pouvoir/` | Les quatre pouvoirs (`Retour`, `Echo`, `Stase`, `Restauration`) |
| `faille/` | Les failles, leurs époques et leur apparition |
| `monde/` + `mixin/` | Mémoire des explosions (un mixin note les blocs juste avant qu'ils ne sautent) |
| `item/` | Les artefacts (objets vanilla habillés de composants) et le traité |
| `fx/` | Particules, sons, entités d'affichage animées |
| `src/main/resources/data/` | Recettes et progrès (datapack intégré) |

Tout ce qui est visuel passe par des **entités d'affichage** (blocs, objets, texte) animées par interpolation
côté client, et par des **Mannequins** (l'entité qui affiche un skin de joueur) pour les échos — d'où l'absence de mod client.

Les entités temporaires portent le tag `chronomancie.ephemere` : après un arrêt brutal du serveur,
celles qui reviennent au chargement du monde sont supprimées, et les créatures restées figées sont libérées.

Garde-fous : le Chronoscope ne restaure que là où il n'y a plus rien (il n'écrase jamais une construction),
ignore les coffres et la TNT, et reprend les objets lâchés par l'explosion qui traînent encore au sol pour éviter les duplications.

## Compiler et tester

Il faut **Java 25**.

```sh
./gradlew :chronomancie:build        # compile + lance les tests en jeu
./gradlew :chronomancie:runGameTest  # uniquement les tests en jeu
```

Le mod est généré dans `chronomancie/build/libs/chronomancie-<version>.jar`.
Les tests (`src/gametest/`) démarrent un vrai serveur avec un joueur simulé et vérifient chaque pouvoir :
retour dans le temps, stase (flèche suspendue, dégâts accumulés), écho, réparation d'une explosion,
faille complète jusqu'aux fragments, et recettes.

## Installer sur un serveur

Dans `mods/` d'un serveur Fabric 26.3 : `chronomancie-<version>.jar` et [Fabric API](https://modrinth.com/mod/fabric-api).
