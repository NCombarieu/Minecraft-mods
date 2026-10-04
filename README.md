# Mods Minecraft

Trois mods Fabric pour **Minecraft 26.3**, côté serveur :

| Mod | Dossier | En bref |
|---|---|---|
| **Hameau** | [`hameau/`](hameau/README.md) | Des villageois qui ont un caractère, une mémoire et une voix, et qui décident eux-mêmes de ce qu'ils font grâce à un modèle de langage |
| **⌛ Chronomancie** | [`chronomancie/`](chronomancie/README.md) | Failles temporelles, vestiges d'autres époques et artefacts pour remonter, figer, dédoubler ou réparer le temps |
| **Premier Mod** | racine du dépôt | Bienvenue, `/heal`, `/fusee`, `/de`, terre chanceuse, mouton arc-en-ciel |

`./gradlew build` compile les trois et lance leurs tests. `outils/chercher-secrets.sh` vérifie qu'aucune clé d'API n'est versionnée ;
l'intégration continue fait les deux à chaque push.

# Premier Mod

Mod Fabric pour **Minecraft 26.3**, côté serveur uniquement (les joueurs n'ont rien à installer).

## Fonctionnalités

| Quoi | Comment | Fichier |
|---|---|---|
| Message de bienvenue | Automatique à la connexion | `events/ModEvents.java` |
| Terre chanceuse 💎 | Casser de la terre : 1 chance sur 50 d'avoir un diamant | `events/ModEvents.java` |
| Mouton arc-en-ciel 🌈 | Clic droit main vide sur un mouton | `events/ModEvents.java` |
| `/heal` | Soigne et nourrit (ops seulement) | `commands/ModCommands.java` |
| `/fusee` 🚀 | T'envoie dans les airs | `commands/ModCommands.java` |
| `/de [faces]` 🎲 | Lance un dé (6 faces par défaut, 2 à 100) | `commands/ModCommands.java` |

## Compiler

Il faut **Java 25**.

```sh
./gradlew build
```

Le mod est généré dans `build/libs/premiermod-<version>.jar`.

## Installer sur un serveur

1. Installer un serveur Fabric 26.3 : https://fabricmc.net/use/server/
2. Mettre dans `mods/` : `premiermod-<version>.jar` et [Fabric API](https://modrinth.com/mod/fabric-api) pour 26.3.

## Ajouter une fonctionnalité

- **Nouvelle commande** : copier un bloc `dispatcher.register(...)` dans `ModCommands.java`.
- **Réagir à un événement** (bloc cassé, clic sur une entité, connexion…) : ajouter un `XxxEvent.register(...)` dans `ModEvents.java`.
  La liste des événements est dans la doc Fabric : https://docs.fabricmc.net/develop/events
