# Rebuild 3 Workshop Android

Prototype Android autonome pour installer les mods Rebuild 3 sans gros collage presse-papiers.

## V0.1 - Overlay first

- téléchargement des fichiers depuis GitHub Pages ;
- overlay au-dessus de Rebuild 3 avec partie, pourcentage et progression totale ;
- clavier Android IME intégré ;
- écriture progressive par petits blocs de 96 caractères ;
- pause, reprise et arrêt ;
- partie suivante sélectionnée automatiquement ;
- aucun root, aucun Shizuku, aucune permission Accessibility.

Le clavier intégré doit être activé une fois dans les paramètres Android puis sélectionné avant l'installation.

InputConnection.commitText() injecte directement le texte dans le champ actif. Cela évite le gros collage unique actuellement tronqué par Rebuild 3 / AIR.

Si Rebuild impose lui-même une limite de taille au champ, le pack Android reste multi-fichier. La version Desktop reste dans un seul fichier.
