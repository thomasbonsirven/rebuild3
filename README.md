# Rebuild 3 — Traduction française Android

Projet pour générer et installer une traduction française de **Rebuild 3: Gangs of Deadsville** sur Android.

## Fonctionnement

Le générateur :
- télécharge les sources officielles 2024 de Rebuild 3 ;
- traduit les fichiers `en_*.properties` vers le français via `deep-translator` ;
- protège les variables dynamiques du jeu (`[Name]`, `{1}`, `[g|...|...]`, etc.) ;
- découpe le résultat en petits fichiers adaptés au presse-papiers Android ;
- génère une page HTML avec un bouton **Copier** pour chaque morceau.

## Test Android

Le dossier `test-fr/` contient deux petits mods déjà traduits pour valider la méthode.

Dans Rebuild 3 :

`Config → Modding → Install Mod → Coller → Okay`

## Génération locale

```bash
python -m pip install -r requirements.txt
python build_rebuild3_fr.py
python validate_pack.py
```

Le site généré se trouve dans :

`rebuild3_fr_output/site/`

## GitHub Actions / GitHub Pages

Le workflow `.github/workflows/build-pages.yml` génère le pack complet et peut le publier sur GitHub Pages.

Avant son premier lancement :

**Settings → Pages → Source → GitHub Actions**

Puis :

**Actions → Build and publish Rebuild 3 FR → Run workflow**

Le ZIP source officiel n'est pas versionné dans le dépôt. Il est téléchargé automatiquement depuis `rebuildgame.com`.
