# Hameau

Mod Fabric pour **Minecraft 26.3**, côté serveur. Chaque villageois devient une personne : un prénom, un caractère, une histoire,
des souvenirs, des amitiés et des rancunes. Il décide lui-même de ce qu'il fait, grâce à un modèle de langage, et il le fait pour
de bon dans le monde : il parle, se déplace, donne, fabrique, bâtit, mine, se bat, se met au service de quelqu'un.

Les joueurs se connectent avec un client sans mod. Pour **entendre** les villageois et leur **parler au micro**, ils installent
[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) ; sans lui, tout passe par le chat écrit.

## Ce que font les villageois

- **Une identité inventée.** À son éveil, le modèle invente le prénom, le caractère, la manie, la façon de parler et l'histoire du
  villageois, d'après la culture de son village, elle aussi inventée. Deux villages ne se ressemblent pas.
- **Une mémoire.** Chacun garde ses souvenirs récents, ses souvenirs marquants et une opinion de chaque personne rencontrée.
  Frapper un villageois, lui offrir un objet ou lui tenir parole laisse une trace, chez lui et chez les témoins.
- **Des actes.** Aller, suivre, fuir, donner, fabriquer, manger, ramasser, récolter, ranger dans un coffre, couper un arbre, casser,
  miner (il creuse sa galerie jusqu'au filon), labourer, poser, écrire une pancarte, actionner une porte ou une cloche, chasser,
  frapper, prendre dans un coffre, bâtir.
- **Des constructions.** Un second modèle dessine le plan, bloc par bloc, et le villageois le bâtit. Il cherche un terrain plat et
  libre ; si un joueur est là, il balise l'emplacement et attend son avis.
- **Une vie de village.** Un villageois peut se donner une place (métier inventé, fonction, titre), inscrire un fait dans la
  mémoire commune du village, entrer au service d'un joueur ou d'un autre villageois.
- **Une voix.** Chaque villageois a sa voix, entendue en 3D depuis l'endroit où il se tient.

## Installation

### Serveur

1. Installer un serveur [Fabric](https://fabricmc.net/use/server/) pour Minecraft 26.3, avec Java 25.
2. Mettre dans `mods/` : `hameau-<version>.jar` et [Fabric API](https://modrinth.com/mod/fabric-api).
3. Pour la voix : ajouter [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) et ouvrir le port **UDP 24454**.
4. Déposer au moins une clé d'API (voir [Clés](#clés)), puis démarrer le serveur.

### Joueurs

Rien pour jouer au chat écrit. Pour la voix : Fabric Loader 26.3, Fabric API et Simple Voice Chat dans le dossier `mods`.
Hameau lui-même ne s'installe pas chez les joueurs.

### Clés

Une clé par service, chacune dans son fichier, sur le serveur : `config/hameau-cles/<nom>.txt`. Le nom est celui du profil de
modèle ou du moteur de voix (`anthropic`, `openai`, `mistral`, `elevenlabs`…). Un profil nommé `openai-luna` utilise la clé
`openai.txt`. Les clés ne se donnent jamais par une commande : le chat est écrit dans les journaux du serveur.

Deux emplacements d'origine restent lus : `config/hameau-cle.txt` (Anthropic) et `config/hameau-voix-cle.txt` (ElevenLabs),
ainsi que les variables `ANTHROPIC_API_KEY` et `ELEVENLABS_API_KEY`.

## En jeu

Parle dans le chat près d'un villageois (à moins de 10 blocs) : celui dont tu cites le prénom te répond, sinon le plus proche.
Avec Simple Voice Chat, regarde-le et parle au micro. Accroupi, clic droit avec un objet en main : tu le lui offres.

### Commandes

`/hameau help` donne la liste en jeu, `/hameau help <commande>` le détail et des exemples.

| Commande | Pour qui | Effet |
|---|---|---|
| `/hameau help [commande]` | tous | La liste des commandes, ou le détail de l'une d'elles |
| `/hameau qui` | tous | Les villageois des environs : prénom, métier, humeur, distance |
| `/hameau village` | tous | Le nom, la culture et la mémoire commune du village |
| `/hameau journal [tout\|livre]` | tous | Ce qui s'est passé depuis ta dernière lecture ; `livre` le donne en livre |
| `/hameau chantier <valider\|ici\|annuler> <prénom>` | tous | Ton avis sur l'emplacement qu'un villageois te montre |
| `/hameau ordres [prénom]` | tous | Le menu d'ordres d'un villageois à ton service |
| `/hameau etat` | tous | Dépense, modèle, villageois éveillés |
| `/hameau voix` | tous | État de la voix, quotas, voix disponibles |
| `/hameau ame [prénom]` | opérateurs | La fiche intime d'un villageois |
| `/hameau penser [prénom]` | opérateurs | Le fait réfléchir tout de suite |
| `/hameau dire <prénom> <texte>` | opérateurs | Lui parler à distance |
| `/hameau faire <prénom> <action> [cible] [; …]` | opérateurs | Lui impose une action, sans passer par le modèle |
| `/hameau personnalite <prénom> <demande>` | opérateurs | Réécrit son caractère selon la demande |
| `/hameau renaitre <prénom\|tous>` | opérateurs | Lui fait inventer une identité neuve ; il garde ses souvenirs |
| `/hameau presenter <prénom>` | opérateurs | Fait d'un étranger (`/summon`, œuf) un habitant du village |
| `/hameau recruter <prénom> [joueur]` | opérateurs | Le met au service de quelqu'un |
| `/hameau liberer <prénom>` | opérateurs | Le congédie |
| `/hameau oubli <joueur>` | opérateurs | Tous les villageois oublient ce joueur |
| `/hameau univers [texte\|rien]` | opérateurs | Le décor du serveur, pris en compte par tous |
| `/hameau modele [profil] [plans <profil>]` | opérateurs | Change de modèle, sans redémarrer |
| `/hameau voix synthese\|transcription <moteur>` | opérateurs | Choisit le service de voix ou de transcription |
| `/hameau voix essai [phrase]` | opérateurs | Fait dire puis transcrire une phrase, pour vérifier |
| `/hameau voix <prénom> [voix]` | opérateurs | La voix d'un villageois, ou lui en donne une autre |
| `/hameau pause`, `/hameau reprendre` | opérateurs | Suspend ou relance les appels aux modèles |
| `/hameau recharger` | opérateurs | Relit les réglages, les textes et les clés |

### Un villageois à ton service

`/hameau recruter <prénom>` le met à ton service d'office ; tu peux aussi le convaincre en lui parlant. Il te suit, t'obéit et
t'entend jusqu'à 24 blocs. Accroupi, main vide, clic droit sur lui : le menu d'ordres s'ouvre, chaque objet est un ordre.

### Chantiers

Près d'un joueur, le villageois balise l'emplacement choisi (contour au sol, poteaux d'angle) et propose trois boutons dans le
chat : valider, bâtir là où tu te tiens, annuler. Sans réponse, il commence au bout de 45 secondes.

Un chantier ouvert est toujours repris. Pour bâtir autre chose, le villageois doit d'abord l'abandonner : dis-lui d'arrêter.

## Réglages

Tout est dans `config/hameau.json`, créé au premier lancement. `/hameau recharger` l'applique sans redémarrer.
Les durées sont en secondes, les distances en blocs.

### Modèles

`modeles` décrit les profils entre lesquels `/hameau modele` bascule. Un profil sans `url` passe par Anthropic ; avec une `url`,
par n'importe quelle API compatible OpenAI (`/chat/completions`) : Mistral, Qwen, OpenAI, OpenRouter, Groq, Ollama.

| Champ | Rôle |
|---|---|
| `url` | Adresse de l'API ; vide pour Anthropic |
| `id` | Identifiant du modèle chez le fournisseur |
| `effort` | Effort de réflexion (`low`, `medium`, `high`) ; vide si le modèle ne raisonne pas |
| `maxTokens` | Longueur maximale d'une réponse |
| `prixEntree`, `prixSortie` | Dollars par million de tokens, pour le suivi de la dépense |

Les réflexions et les plans de construction ont chacun leur modèle. Les prix doivent être renseignés : à zéro, la dépense n'est
pas comptée et les plafonds ne protègent plus rien.

### Dépense et rythme

| Réglage | Défaut | Rôle |
|---|---|---|
| `plafondJournalierUsd`, `plafondTotalUsd` | 2, 18 | Au-delà, les villageois se taisent |
| `intervallePensee` | 180 | Temps moyen entre deux réflexions de routine |
| `delaiMinEntrePensees` | 12 | Délai minimal entre deux réflexions du même villageois |
| `intervalleIncident` | 300 | Temps moyen entre deux petits imprévus ; 0 pour aucun |
| `rayonActif` | 40 | Seuls les villageois à cette distance d'un joueur réfléchissent |
| `maxVillageoisActifs` | 10 | Nombre de villageois qui vivent en même temps |
| `appelsSimultanes` | 2 | Appels aux modèles en parallèle |
| `vieHorsLigne` | non | Le village vit-il sans joueur connecté ? |

### Voix (`voix`)

| Réglage | Défaut | Rôle |
|---|---|---|
| `synthese`, `transcription` | `elevenlabs` | Le moteur qui parle et celui qui écoute |
| `moteurs` | | Les services disponibles : `type` (`elevenlabs` ou `openai`), `url`, modèles, voix, tarifs |
| `distance` | 24 | Portée d'une réplique adressée à un joueur |
| `distanceBavardage` | 8 | Une réplique entre villageois n'est dite que si un joueur est à cette distance |
| `ecoute` | oui | Les villageois entendent-ils les micros ? |
| `plafondCaracteresParJour` | 200000 | Garde-fou sur la synthèse |
| `plafondSecondesEcouteParJour` | 900 | Garde-fou sur la transcription |

Une réplique n'est synthétisée que si un joueur équipé de Simple Voice Chat est à portée.

### Constructions (`batir`)

| Réglage | Défaut | Rôle |
|---|---|---|
| `largeurMax`, `hauteurMax` | 21, 16 | Taille maximale d'un plan |
| `effort` | `medium` | Effort de réflexion pour les plans, hors Anthropic |
| `ticksParBloc` | 5 | Rythme de pose (20 ticks = 1 seconde) |
| `materiauxGratuits` | oui | Sinon le bâtisseur doit avoir chaque bloc en poche |
| `apercuSecondes` | 45 | Attente de l'avis du joueur ; 0 pour bâtir sans demander |

### Autonomie (`autonomie`)

`frapper`, `casser`, `poser`, `prendreDansCoffres`, `batir` : ce que les villageois ont le droit de faire d'eux-mêmes.

### Textes

Les consignes données aux modèles sont dans `config/hameau/` :

| Fichier | Contenu |
|---|---|
| `esprit.txt` | Comment les villageois se comportent |
| `naissance.txt` | Quel genre de personnes inventer |
| `village.txt` | Quel genre de villages inventer |
| `univers.txt` | Le décor propre au serveur (aussi réglable par `/hameau univers`) |

Le format des réponses et la liste des actions restent dans le code : le mod en dépend.

## Données

Les âmes, les villages, le journal et le compteur de dépense sont dans `<monde>/hameau/ames.json`, sauvegardés toutes les cinq
minutes et à l'arrêt du serveur.

## Développement

Il faut Java 25. Depuis la racine du dépôt :

```sh
./gradlew :hameau:build          # compile, lance les tests unitaires et les tests en jeu
./gradlew :hameau:test           # tests unitaires seuls
./gradlew :hameau:runGameTest    # tests en jeu seuls
outils/chercher-secrets.sh       # vérifie qu'aucune clé n'est versionnée
```

Le jar est produit dans `hameau/build/libs/`. Il embarque le SDK Anthropic ; Simple Voice Chat est une dépendance facultative.

### Organisation du code

| Classe | Rôle |
|---|---|
| `Hameau` | Point d'entrée : branche les événements et la boucle de jeu |
| `HameauConfig`, `Textes`, `Cles` | Réglages, textes des consignes, clés d'API |
| `Ame`, `Ames` | Ce qu'est un villageois ; le registre des âmes et des villages, la sauvegarde |
| `Perception` | La fiche de situation donnée au modèle |
| `Cerveau` | Les appels aux modèles et la lecture de leurs réponses |
| `Vie` | Le rythme : qui réfléchit, quand, et ce que deviennent les décisions |
| `Actions` | Les gestes dans le monde, suivis de tick en tick |
| `Chantiers` | Les constructions : plan, choix du terrain, aperçu, pose |
| `Evenements` | Ce que le monde apprend aux villageois : paroles, coups, morts, cadeaux |
| `Corps`, `Bulles` | Le corps humain des villageois, les bulles de texte et les émotions |
| `Voix`, `voix/VoixPlugin` | Synthèse, transcription, et le pont avec Simple Voice Chat |
| `MenuOrdres` | Le menu d'ordres d'un villageois au service d'un joueur |
| `HameauCommande` | Les commandes `/hameau` |
| `Budget` | Le suivi de la dépense |

L'historique des versions est dans [CHANGELOG.md](CHANGELOG.md).
