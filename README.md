# Tello Pilot

App Android maison pour piloter un DJI Ryze Tello. Elle remplace l'app officielle, qui n'est plus maintenue.

Elle parle au drone avec le **protocole binaire natif**, celui qu'utilise l'app officielle. Ce protocole donne accès :
- au mode rapide ;
- à la télémétrie complète ;
- aux photos 5 Mpx prises par le drone ;
- aux modes de vol.

## Installer
1. Ouvre l'onglet **Releases** du dépôt et télécharge `TelloPilot.apk` depuis la dernière release.
2. Ouvre le fichier pour l'installer. Une nouvelle version s'installe par-dessus l'ancienne.

## Voler
1. Allume le Tello, puis connecte le téléphone au Wi-Fi `TELLO-xxxx`. L'app se connecte toute seule.
2. Bouton en haut à gauche, puis **glisse** pour décoller. Même geste pour atterrir.
3. Pilote avec les **joysticks flottants** : pose le pouce n'importe où sur la moitié gauche ou droite de l'écran.
   - **Mode 2** (par défaut) : le pouce gauche règle l'altitude et la rotation, le droit le déplacement.
   - **Mode 1** : modifiable dans les paramètres.
4. Touche la pastille **LENT / RAPIDE** en haut pour changer de vitesse.

### Modes de vol
| Mode | Effet |
|---|---|
| Lance et vole | Lance le drone : il se stabilise tout seul |
| Atterrissage main | Il se pose dans ta main |
| Rebond | Il monte et descend tout seul au-dessus d'une surface |
| Flips 8D | Glisse sur l'écran pour faire un flip dans 8 directions |
| 360° | Il fait un tour complet sur place en filmant |
| Grand angle | Il recule et monte en filmant |

### Pilote IA (d1-2B, 100 % local)
Tu donnes une consigne en langage naturel, par exemple « va vers la porte ». Le modèle de décision **d1-2B** de Liquid AI
pilote alors le drone **pas à pas** :
1. le drone s'immobilise ;
2. d1 regarde l'image et choisit une action parmi 9 (avancer, tourner, monter, glisser, reculer, terminé…) ;
3. le drone exécute un petit mouvement, puis le cycle recommence.

- Le modèle tourne **sur le téléphone**, avec `llama-server` (llama.cpp) compilé pour Android et embarqué dans l'APK.
  Il ne faut aucune connexion Internet pendant le vol.
- Premier lancement : bouton IA à droite de l'écran, puis « Télécharger le modèle ». Il pèse 1,3 Go et se télécharge **en Wi-Fi avec Internet** (pas celui du drone).
- « Analyser l'image » montre la décision de d1 sans faire bouger le drone. C'est pratique pour tester les consignes au sol.
- Sécurités :
  - vitesse lente forcée ;
  - 40 étapes au maximum ;
  - altitude maximale respectée ;
  - arrêt si la batterie tombe sous 15 % ou si la liaison est perdue ;
  - **reprise en main immédiate dès que tu touches un joystick**.

### Caméra
- **Photo** : 5 Mpx, prise directement par le drone.
- **Vidéo** : enregistrée en MP4, à la qualité d'origine.
- Les fichiers vont dans la Galerie, dossier `TelloPilot`.

### Sécurité
- Maintenir le bouton STOP 1 seconde (un anneau se remplit) coupe les moteurs immédiatement : le drone tombe.
- Si l'app passe en arrière-plan pendant le vol, le drone atterrit.

## Technique
- `drone/` contient le protocole : paquets, CRC, télémétrie, journaux IMU et transfert des photos. Il s'appuie sur les travaux open source **TelloPy** et **Gobot** (licence Apache-2.0).
- `video/` reconstitue le flux H.264 image par image et le décode avec le décodeur matériel du téléphone. Un chien de garde relance la vidéo si l'image se fige, et l'enregistrement MP4 se fait sans ré-encodage.
- `ai/` contient le pilote IA : téléchargement du modèle, lancement de llama-server, appels à l'API `/v1/systemone` et boucle de navigation.
- `ui/` contient le HUD, entièrement dessiné en code.

GitHub Actions lance les tests du protocole, compile l'APK puis le publie dans les Releases, à chaque push sur `main`.
