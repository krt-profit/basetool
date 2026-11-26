### Security

- **Die Signaturprüfung der Container-Images läuft mit cosign 3.1.6.** Build, Freigabe und Deploy
  nutzen dieselbe Version; als Signatur zählt nur noch, was `cosign sign` erzeugt hat, kein anderer
  signierter Anhang. Auf dem Server wird das Programm beim nächsten Ansible-Lauf ausgetauscht.
