# Keyboard word lists

The `app/src/main/assets/keyboard/words_*.txt` lists are derived from the
`wordfreq` 3.1.1 `top_n_list()` output (copyright Robyn Speer, Apache-2.0).
See [the license notice](wordfreq-license.txt) and
https://github.com/rspeer/wordfreq for source and data provenance.

We keep only single words made of letters (with interior apostrophes), remove
duplicates and exclude longer entries. Lists are in descending frequency order.
English has 20,000 entries; French, Spanish, Portuguese, German and Italian
have 8,000 each; Indonesian and Turkish have 5,000 each. Other languages use
the smaller bundled starter packs and an installed Android spell checker when
available. The full lists load off the IME thread; starter words are ready at
once. Offline suggestions and corrections need no network connection.

Users may **opt in** to online English completions in Keyboard Settings. The
[suggestion API from Datamuse](https://www.datamuse.com/api/) currently offers
`https://api.datamuse.com/sug?s=<prefix>&max=8` without a key. Only a single
3–24 character English word prefix is sent after a pause while typing regular
text; passwords, email/URL fields, no-learning fields and recognizable login
fields are excluded. Results are cached briefly in memory. The optional API
is not required for correction, and an outage never disables offline words.
Datamuse's published access policy may change (it announces an API-key
requirement for February 2027), so the offline path must remain reliable.
