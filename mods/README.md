# Mods

## config

Mods qui changent les variables de gameplay supportées par Rebuild 3.

Chaque fichier doit au minimum contenir :

```ini
mod_type = config
mod_name = Nom du mod
mod_description = Description
```

## language

Mods qui remplacent des chaînes existantes : traduction, renommage ou réécriture
des textes d'événements et de quêtes.

Chaque pack de langue doit définir au moins une fois :

```properties
mod_type = language
mod_name = Nom du pack
mod_description = Description
mod_language_name = Français
mod_locale_id = FR
```

Voir `docs/MODDING.md` pour les limites du moteur.
