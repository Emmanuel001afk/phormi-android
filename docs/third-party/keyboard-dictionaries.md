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
available. No network request is made to predict a word.
