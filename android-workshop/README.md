# Rebuild 3 Workshop Android

Application Android compagnon pour installer les mods et traductions Rebuild 3 sans gros collage presse-papiers.

## V0.6 — Keyboard first

La V0.2 privilégie un clavier Android spécialisé plutôt qu'un overlay.

Fonctions :

- téléchargement du catalogue depuis GitHub Pages ;
- pack complet en un fichier pour tester l'injection progressive ;
- pack Android multi-fichiers en fallback ;
- TEST UI court ;
- clavier IME intégré ;
- bouton **Bloc** : injecte 128 caractères ;
- bouton **AUTO / PAUSE** : injecte progressivement par blocs de 128 caractères ;
- boutons **◀ / ▶** pour changer de fichier ;
- pourcentage réel de progression ;
- aucun root ;
- aucun Shizuku ;
- aucune permission Accessibility ;
- aucune permission overlay.

Le clavier utilise `InputConnection.commitText()`. Il ne place pas le mod complet dans le presse-papiers.

## Utilisation

1. Installer l'APK.
2. Activer **Rebuild 3 Workshop Keyboard** dans les paramètres Android.
3. Télécharger un pack dans l'application.
4. Ouvrir Rebuild 3.
5. `Config → Modding → Install Mod`.
6. Toucher le champ blanc.
7. Sélectionner le clavier Workshop.
8. Utiliser **Bloc** ou **AUTO**.
9. À 100 %, valider avec **Okay**.

Le mode multi-fichiers permet de contourner une éventuelle limite interne du champ Rebuild/AIR. La version PC/Desktop reste publiée en un seul fichier.
