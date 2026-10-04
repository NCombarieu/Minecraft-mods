# Historique de Hameau

## 0.10.1

- Minage : le villageois avance réellement dans sa galerie. Il se croyait arrivé sans avoir bougé, ce qui le faisait piétiner.
- Une décision sans action vaut « rien » au lieu de provoquer une erreur.
- Tests unitaires (lecture des réponses, sons, réglages, dépense) et tests en jeu (âmes, chantiers, mine, service, journal).

## 0.10.0

- Plans de construction avec blocs orientés (escaliers, dalles, piliers, échelles, lanternes suspendues), jusqu'à 21 blocs de
  côté et 16 de haut, et des consignes d'architecte plus exigeantes.
- Aperçu des chantiers : près d'un joueur, le villageois balise l'emplacement et attend son avis (valider, déplacer, annuler).
- Menu d'ordres en coffre pour qui a un villageois à son service.
- Journal du village : `/hameau journal`, aussi en livre.
- La voix ne dit plus que ce qui s'adresse à un joueur ou se dit tout près de lui.
- Le coût de la voix, de la transcription et des modèles OpenAI entre dans la dépense suivie.

## 0.9

- **0.9.4** — Plans plus fournis hors Anthropic (réponse JSON stricte, effort de réflexion réglable). Un chantier ouvert est
  toujours repris ; pour en changer, le villageois doit d'abord l'abandonner.
- **0.9.3** — Les plans laissent quatre minutes au modèle. Les grandes bâtisses acceptent un terrain un peu plus pentu.
- **0.9.2** — Second essai quand un service compatible OpenAI est débordé.
- **0.9.1** — Limite de tokens au nom attendu par les modèles récents d'OpenAI.
- **0.9.0** — Modèles et services de voix au choix : Anthropic ou toute API compatible OpenAI pour les réflexions, ElevenLabs
  ou API compatible OpenAI pour la voix et la transcription. Une clé par service dans `config/hameau-cles/`.

## 0.8.0

- Un chantier cherche le terrain plat et libre le plus proche.
- Nouvelles actions : miner, récolter, ramasser, ranger dans un coffre, manger, chasser.
- Un villageois peut entrer au service de quelqu'un, se donner une place au village et inscrire un fait dans sa mémoire commune.

## 0.7

- **0.7.2** — Des villageois et des villages plus accueillants.
- **0.7.1** — Les villageois entendent les joueurs au micro.
- **0.7.0** — Les villageois parlent à voix haute, par Simple Voice Chat.

## 0.6

- **0.6.1** — Textes des consignes modifiables dans `config/hameau/`, et `/hameau univers`.
- **0.6.0** — Personnalités et cultures de village inventées par le modèle. `/hameau help`, `village`, `personnalite`,
  `renaitre`, `presenter`. Le chat des joueurs est redistribué en messages système.

## 0.5 et avant

Première version : identité, mémoire, relations, décisions par Claude, actions dans le monde, corps humain, constructions.
