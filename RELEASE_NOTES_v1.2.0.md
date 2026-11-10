# 📜 Notes de Version — PaperSeasons v1.2.0
### *"Atmospheric, Almanac & Events Update"*

Une mise à jour majeure axée sur l'immersion visuelle, l'ergonomie joueur, les événements climatiques rares et l'équilibrage du système de survie thermique, toujours optimisée pour **Raspberry Pi 5** et **Playit.gg** ($O(1)$ CPU, zéro paquet réseau superflu).

---

## 🌟 1. Almanach des Saisons Interactif (`/season` ou `/season calendar`)

Fini les lignes de chat complexes : les joueurs ont désormais accès à un menu GUI 27 slots immersif et interactif en tapant simplement `/season` :

* **⏳ Horloge Astronomique (Slot 4) :**
  * Affiche l'Année, le Jour actuel, l'Heure solaire précise (`00h00` à `23h59`), la sous-saison active et son pourcentage d'avancement.
  * Indique en temps réel l'événement climatique en cours s'il y en a un.
* **🍂 Les 4 Piliers Saisonniers (Slots 10, 12, 14, 16) :**
  * **Printemps, Été, Automne, Hiver** : l'icône de la saison en cours brille d'un enchantement scintillant.
  * Survoler chaque saison détaille :
    * Les **3 sous-saisons progressives** (`EARLY`, `MID`, `LATE`) et leurs modificateurs de température.
    * Le **guide agricole complet** : cultures favorisées ($+35\%$ à $+40\%$) et cultures ralenties.
    * Les **règles d'élevage et de pêche** propres à la saison.
* **🌡️ Bilan Thermique Personnel en Direct (Slot 22) :**
  * Consultez d'un coup d'œil votre température exacte, votre statut d'abri (`SkyLight`), vos apports de chaleur de blocs/torches, votre niveau de mouillure `💧`, votre jauge de gel `❄️`, ainsi que vos protections d'armure et buffs de repas/bières BreweryX.
  * **Interactivité :** Cliquez sur la boussole pour actualiser votre diagnostic thermique en direct !

---

## 🍃 2. Particules Ambiantes 100% Côté Client (Zéro lag Playit.gg)

Grâce aux nouveaux codecs de biomes intégrés au Data Pack `paper_seasons_dp` (compatible Vanilla 1.21.1 et Paper/Terralith 26.2), le monde s'anime de particules d'ambiance natives gérées directement par le client des joueurs :

* 🌸 **Printemps (`SPRING_MID`, `SPRING_LATE`) :** Légère brise de pétales de cerisier (`cherry_leaves`).
* ☀️ **Été (`SUMMER_MID`) :** Pollen flottant et air chaud d'été (`spore_blossom_air`).
* 🍁 **Automne (`AUTUMN_MID`, `AUTUMN_LATE`) :** Chute de feuilles d'arbres dorées et cuivrées (`cherry_leaves` et `pale_oak_leaves`).
* ❄️ **Hiver (`WINTER_EARLY`, `WINTER_MID`, `WINTER_LATE`) :** Bourrasques de givre et cendres glaciales (`white_ash`).

---

## ⚡ 3. Événements Climatiques Saisonniers Rares (`SeasonalEvent`)

Chaque matinée, le climat a désormais **20% de chances** de déclencher un événement climatique spécial marquant la journée :

| Événement | Saison | Effets Météo & Gameplay |
| :--- | :--- | :--- |
| **🌸 Floraison Printanière**<br>`SPRING_BLOOM` | Printemps | La nature explose : la vitesse de pousse des fleurs, arbres et cultures est **doublée**, et les chances de naissances gémellaires lors de l'élevage atteignent **50%** ! |
| **🔥 Vague de Canicule**<br>`HEATWAVE` | Été | Soleil écrasant sans un nuage : **$+7.0^\circ\text{C}$** en pleine journée (08h-16h). L'eau des vêtements sèche deux fois plus vite, attention aux coups de chaud ! |
| **🌫️ Brume d'Automne**<br>`AUTUMN_MIST` | Automne | Pluie fine et brumeuse : pousse des champignons et baies boostée de **$+50\%$**, et activité des poissons décuplée (pêche **$+40\%$ plus rapide**). |
| **🌨️ Blizzard Polaire**<br>`BLIZZARD` | Hiver | Tempête de neige extrême : **$-8.0^\circ\text{C}$** supplémentaires en extérieur, effet *Wind Chill* doublé, bourrasques violentes et accumulation rapide de couches de neige au sol. |

> 🛠️ *Les administrateurs peuvent déclencher ou stopper manuellement un événement via `/season event <NONE|BLIZZARD|HEATWAVE|AUTUMN_MIST|SPRING_BLOOM>`.*

---

## 🛡️ 4. Équilibrage Réaliste des Armures ($O(1)$)

Pour permettre aux aventuriers de s'adapter sans devoir sacrifier leur protection au combat :

1. **Protection contre le feu (`FIRE_PROTECTION`) en Été :**
   * Chaque niveau de l'enchantement sur vos pièces d'armure dissipe la chaleur excessive de **$-0.75^\circ\text{C}$ par niveau** lorsque la température ambiante dépasse $30^\circ\text{C}$.
2. **Isolation Métallique par le Cuir ou les Garnitures (`ArmorTrim`) en Hiver :**
   * Les armures lourdes en Fer ou Diamant conduisent naturellement le froid ($-4.0^\circ\text{C}$ en set complet).
   * Porter **au moins une pièce en cuir** (ex: bottes ou pantalon) **OU** posséder une **Garniture d'armure** en Quartz, Or, Améthyste, Cuivre ou Résine **réduit ce malus de moitié** ($-2.0^\circ\text{C}$ au lieu de $-4.0^\circ\text{C}$).

---

## ❄️ 5. Correctifs Majeurs de Survie Hivernale

* **Dégâts Directs d'Hypothermie :**
  * Dès qu'un joueur reste sous $0.0^\circ\text{C}$, les bords de l'écran se givrent de manière stable et continue.
  * Des **dégâts directs de gel (`DamageType.FREEZE`)** de $1.0$ dégât (0.5 cœur) par cycle s'appliquent, s'élevant à $2.0$ dégâts (1 cœur) en cas de grand froid glacial ($\le -10^\circ\text{C}$), accompagnés de sons de tremblement et de givre.
* **Forçage de la Neige Météo en Hiver :**
  * En surface ($Y \ge 48$), les précipitations hivernales tombent désormais rigoureusement sous forme de **flocons de neige** sur tous les biomes, y compris ceux générés par Terralith.
  * Dépôt progressif de couches de neige (`Material.SNOW`) et gel des plans d'eau calme (`Material.ICE`) autour des joueurs en extérieur, tout en préservant les zones chauffées (`Light >= 12`) et les serres vitrées.

---

## 🎮 Commandes Mises à Jour

* `/season` ou `/season calendar` : Ouvre l'Almanach des Saisons interactif (GUI 27 slots).
* `/season status` : Bilan textuel complet dans le chat (conservé pour la console et les scripts).
* `/season event <type>` : Déclenche un événement météo rare (`BLIZZARD`, `HEATWAVE`, `AUTUMN_MIST`, `SPRING_BLOOM`, `NONE`).
* `/season nextday` : Avance le calendrier d'un jour.
* `/season set <saison|sous-saison>` : Force une saison ou sous-saison précise.
* `/season reload` : Recharge à chaud `config.yml` et reconstruit tous les caches $O(1)$.
