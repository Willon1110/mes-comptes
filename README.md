# Mes Comptes

Application personnelle de suivi de budget (4 comptes EUR/CHF, prévu / réel, résumés mensuel et annuel).

- Hébergée sur GitHub Pages (branche `gh-pages`).
- Données stockées dans Supabase, **chiffrées de bout en bout** sur l'appareil (AES-GCM 256, clé dérivée de la phrase secrète par PBKDF2). Ce dépôt ne contient aucune donnée ni aucun secret : seule la clé publique Supabase y figure, ce qui est prévu par Supabase.
- `supabase.sql` : structure de la table et règles de sécurité (référence).
