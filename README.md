# Rebuild 3 — FR Android & Modding

Outils pour **Rebuild 3: Gangs of Deadsville** : traduction française Android,
mods de configuration et modèles de remplacement de textes de quêtes/événements.

## Test Android immédiat

Le site de test est publié automatiquement sur GitHub Pages :

https://thomasbonsirven.github.io/rebuild3/

Sur Android, ouvre cette page dans Chrome/Firefox, appuie sur **Copier**, puis :

`Rebuild 3 → Config → Modding → Install Mod → appui long → Coller → Okay`

> Rebuild 3 mobile n'installe pas un mod depuis une URL : l'URL sert uniquement à
> ouvrir la page et copier le texte du mod dans le presse-papiers.

## Traduction française complète

Le workflow manuel **Build full French pack** :

1. télécharge les sources officielles 2024 ;
2. traduit les fichiers `en_*.properties` ;
3. protège les variables `[Name]`, `{1}`, `[g|...|...]`, etc. ;
4. valide les clés ;
5. découpe le pack pour Android ;
6. publie le résultat sur GitHub Pages.

Pour le lancer :

`Repository → Actions → Build full French pack → Run workflow`

Ce menu est dans l'onglet **Actions du dépôt**, pas dans `Settings → Pages`.

## Modding

- `mods/config/` : variables de gameplay et difficulté.
- `mods/language/` : traduction / remplacement de textes.
- `docs/MODDING.md` : capacités et limites techniques.

Le système de mod officiel permet les mods `config` et `language`.
Il permet de réécrire le texte d'une quête existante, mais pas d'ajouter une nouvelle
logique de quête sans modifier le code du jeu.

## Génération locale

```bash
python -m pip install -r requirements.txt
python build_rebuild3_fr.py
python validate_pack.py
```

Sortie : `rebuild3_fr_output/site/`
