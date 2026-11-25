# Sons de réussite

Le motif est « Impulsion précise », l’assemblage A retenu pendant la conception du #13. Il combine l’attaque de « Précision » et le mouvement stéréo d’« Impulsion », deux sorties de [Stable Audio 3 Medium](https://huggingface.co/stabilityai/stable-audio-3-medium). Les variantes proviennent de découpes, de changements de vitesse et de fondus appliqués à cet assemblage.

Les trois builds embarquent les mêmes WAV PCM stéréo 44,1 kHz, stockés dans [sound-assets/raw](sound-assets/raw). Les niveaux ci-dessous sont des atténuations par rapport au prototype A.

| Son | Événement | Durée | Niveau |
|---|---|---|---|
| Déclic | Validation d’un J | 0,52 s | −8 dB |
| Coché | Devoir terminé (flux #14) | 0,30 s | −9 dB |
| Envol | Programme du soir entièrement terminé | 1,20 s | −4 dB |
| Nouvel élan | Dernière séance de la sélection de reprise validée | 0,90 s | −6 dB |
| Étincelle | Flamme du jour gagnée | 0,61 s | −6 dB |
| Plus haut | Record de série précédent battu | 1,07 s | −4 dB |
| Métamorphose | Dernier J du programme d’un cours validé | 1,43 s | −3 dB |

Une seule célébration sonore par validation. Ordre de priorité décroissant : Métamorphose, Plus haut, Envol, Nouvel élan, Étincelle, Déclic. Le premier gain initialise le record. Les validations répétées et les échecs d’enregistrement restent silencieux.

Réglages → Sons permet de désactiver les effets et d’écouter « Impulsion précise ». iOS respecte le mode silencieux via la catégorie ambiante. Android utilise le canal de sonification avec le volume système et les modes silencieux et vibreur. Sur ordinateur, le volume suit la sortie système.
