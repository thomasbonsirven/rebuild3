# Modding Rebuild 3

Ce dépôt couvre les formats officiellement supportés par Rebuild 3.

## Capacités réelles

| Besoin | Support officiel | Format |
|---|---|---|
| Modifier difficulté / ressources / zombies / fréquence d'événements | Oui | `mod_type = config` |
| Modifier ou traduire les textes | Oui | `mod_type = language` |
| Réécrire le texte d'une quête ou d'un événement existant | Oui | `language`, en réutilisant les IDs existants |
| Ajouter une nouvelle quête avec logique, déclencheurs et récompenses | Non | Pas exposé par l'API de mod standard |
| Ajouter un nouveau type de mission, bâtiment ou événement logique | Non | Nécessite une modification du code du jeu |

La documentation officielle décrit uniquement les mods `config` et `language`.

## Android

Sur Android, un mod est installé en collant son texte dans :

`Config → Modding → Install Mod`

Il n'y a pas d'installation directe par URL.

## Dossiers de ce dépôt

- `mods/config/` : mods de gameplay / équilibrage.
- `mods/language/` : remplacements de textes.
- `test-fr/` : petit pack FR de validation Android.
- `build_rebuild3_fr.py` : génération de la traduction française complète.

## Réécriture d'une quête

Les fichiers officiels `en_quests.properties` et `en_missionQuestTypes.properties`
contiennent les textes des quêtes existantes. Un mod `language` peut remplacer leurs
valeurs en gardant exactement les mêmes clés.

Exemple :

```properties
mod_type = language
mod_name = Quête réécrite
mod_description = Réécrit le texte d'une quête existante
mod_language_name = English
mod_locale_id = EN

quest_fatherHouse_title = Fouiller la maison abandonnée
quest_fatherHouse_action = fouiller la maison
quest_fatherHouse_tooltip = Chercher des indices dans la maison
```

Cela change l'affichage, **pas la logique de la quête**.

## Nouvelles quêtes

Le format public ne permet pas de déclarer de nouveau script de quête, condition,
déclencheur ou récompense. Les fichiers `en_quests.properties` sont des ressources
de texte : ils exposent des chaînes utilisées par la logique déjà compilée dans le jeu.

Une extension réelle du moteur de quêtes demanderait une voie expérimentale distincte,
basée sur l'analyse/modification du code AIR/SWF. Elle n'est volontairement pas mélangée
avec les mods standards de ce dépôt.
