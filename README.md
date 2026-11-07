# Tello Pilot

App Android maison pour piloter un DJI Ryze Tello. Elle remplace l'app officielle, qui n'est plus maintenue.

Elle utilise le SDK texte officiel du Tello, envoyé en UDP :
- `192.168.10.1:8889` pour les commandes ;
- port `8890` pour l'état du drone ;
- port `11111` pour la vidéo H.264.

## Installer
1. Ouvre l'onglet **Releases** du dépôt et télécharge `TelloPilot.apk` depuis la dernière release.
2. Ouvre le fichier et autorise l'installation depuis le navigateur si Android te le demande.

GitHub Actions compile l'APK à chaque push sur `main`.

## Voler
1. Allume le Tello, puis connecte le téléphone au Wi-Fi `TELLO-xxxx`.
2. Si Android signale « pas d'accès Internet », choisis de rester connecté.
3. Ouvre l'app et appuie sur **Connecter** : la vidéo et la télémétrie apparaissent.
4. Appuie sur **Décoller**.

Les joysticks suivent le mode 2 :
- **gauche** : monter ou descendre, et tourner sur place ;
- **droite** : avancer ou reculer, et se décaler à gauche ou à droite.

| Bouton | Action |
|---|---|
| Vitesse | Change la vitesse : 30, 60 ou 100 % |
| Photo | Enregistre une capture dans Images/TelloPilot |
| Flip | Fait un flip (il faut plus de 50 % de batterie) |
| STOP (appui long) | Coupe les moteurs immédiatement : le drone tombe |

Si l'app passe en arrière-plan pendant le vol, le drone atterrit automatiquement.
