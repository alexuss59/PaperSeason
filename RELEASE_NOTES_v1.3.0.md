# 📜 Notes de Version — PaperSeasons v1.3.0
### *"Survival Crafting, Foraging & Morning Forecast Update"*

Mise à jour majeure de survie thermique et de gameplay pour **PaperSeasons** sur **Raspberry Pi 5** et **PaperMC 26.2** (100% compatible Vanilla, zéro mod/resource pack requis côté client).

---

## ⚒️ 1. Artisanat d'Isolation Thermique des Armures

Les joueurs peuvent désormais adapter n'importe quelle armure (Cuir, Mailles, Fer, Or, Diamant, Netherite) au climat extrême grâce à l'Enclume, **sans jamais perdre leurs enchantements, leur durabilité ni leurs ornements (`ArmorTrim`)** :

* **❄️ Doublure Polaire (`WARM`, +2.5°C par pièce en Hiver/Automne) :**
  * **Enclume :** `[Armure]` + `[Bloc de Laine (n'importe quelle couleur) OU Peau de lapin]`
  * **Coût :** 3 niveaux d'expérience.
  * **Effets :** Lore `<aqua>❄️ Doublure Polaire (+2.5°C)</aqua>` et tag PDC `paperseasons:thermal_lining`. Cumulable jusqu'à **+10.0°C** en set complet et divise par deux la conduction du froid des armures métalliques lourdes.

* **☀️ Doublure Respirante (`COOL`, -2.5°C par pièce en Été ou > 30°C) :**
  * **Enclume :** `[Armure]` + `[Boule de neige, Glace OU Bambou]`
  * **Coût :** 3 niveaux d'expérience.
  * **Effets :** Lore `<gold>☀️ Doublure Respirante (-2.5°C)</gold>` et tag PDC `paperseasons:thermal_lining`. Cumulable jusqu'à **-10.0°C** en set complet pour braver les canicules en armure sans suffoquer d'insolation.

---

## 🍲 2. Cuisine de Survie : Soupe Forestière Épicée

Nouvelle recette d'artisanat (`ShapedRecipe`) ajoutée à la table de craft :
* **Recette :**
  * Ligne du haut : `[Champignon brun ou rouge]` + `[Patate cuite]` + `[Baies sucrées]`
  * Ligne du bas : `[Bol]` au centre
* **Propriétés thermiques :**
  * Procure un puissant réchauffement corporel de **+6.0°C pendant 3 minutes (180s)** hors été.
  * Le meilleur allié pour braver les blizzards et explorer les sommets enneigés.

---

## 🌿 3. Cueillettes & Butins de Saison

Des ressources comestibles exclusives peuvent désormais être récoltées naturellement en explorant le monde :

* **🍂 Automne :**
  * **🍄 Champignon d'Automne Doré (4% de chance) :** En cassant des feuilles, fougères ou buissons. Consommable conférant **+3.0°C pendant 90s**.
  * **🍒 Baies Sauvages Sucrées (4% de chance, 1 à 3 baies) :** En cassant des feuilles, fougères ou buissons. Consommable conférant **+3.0°C pendant 90s**.
* **☀️ Été :**
  * **🍏 Pomme Juteuse d'Été (3% de chance) :** En cassant des feuilles de Chêne ou Chêne Noir. Confère un rafraîchissement immédiat de **-4.0°C pendant 90s**, remet à zéro la jauge d'épuisement de canicule et dissipe l'effet de faim.
* **❄️ Hiver :**
  * **🐟 Prise des Glaces (20% de chance à la pêche) :** En pêchant du cabillaud ou du saumon sous le froid hivernal. Double immédiatement l'expérience gagnée et fournit un poisson enrichi conférant **+4 nourriture** et **+4 saturation** supplémentaires.

---

## 🌅 4. Bulletin Météo au Réveil

Dormir dans un lit passe la nuit et déclenche un bulletin matinal élégant pour tous les joueurs réveillés (`TimeSkipEvent` / `NIGHT_SKIP`) :
* **Chat :** Affiche le jour du calendrier, l'année, la sous-saison actuelle avec sa phase (`Début`, `Milieu`, `Fin`), la tendance climatique de la journée (`Ensoleillé`, `Pluvieux`, `Chutes de neige`, `Blizzard polaire`, `Canicule écrasante`, `Brume`, `Floraison`) et la température ressentie matinale.
* **Sous-titre à l'écran :** Un bandeau discret et immersif apparaît au lever du jour sans encombrer la vue.

---

## 📖 5. Almanach des Saisons Enrichi (`/season` — Slot 24)

* Ajout au slot 24 d'une icône d'enclume **⚒️ Artisanat Thermique & Cueillettes** détaillant toutes les recettes d'isolation d'armure, la soupe forestière et les cueillettes disponibles selon la saison en cours.
