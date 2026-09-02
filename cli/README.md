# aicoin — the command-line wallet and AI client

Everything the browser wallet page does, plus the two things a terminal is better at: making AI
calls through the proxy, and running a **consortium** call, where every configured model answers
one request and then reviews the answer until nobody objects.

```
cd cli && go build -o ~/.local/bin/aicoin .
```

Go 1.24+, no dependencies outside the standard library.

## The short version

```
$ cd ~/src/my-project
$ aicoin "why does the build fail on a clean checkout?" -f "*.go"

$ aicoin .          # or: open a session here and keep asking
aicoin ▸ what does the retry logic in the client do?
aicoin ▸ and is the backoff bounded?
```

With no command word, the whole line is a question for the panel: every configured model answers
it, an editor merges the answers, and then all of them review the result until a round comes back
with no comments. The files in the working directory are listed for them by default, and `-f`
includes the contents of the ones you name.

`aicoin .` opens a **session** on that directory instead: the same thing per question, but each one
carries what was already asked and answered, so follow-ups mean something. Ctrl-D leaves.

Ask it to *change* something and it proposes the change rather than describing it:

```
$ aicoin "create an empty scratch.txt"
the panel proposes 1 change(s) in .:
  create   scratch.txt (0 bytes)
apply? [y/N]
```

## First run

```
aicoin new        # creates ~/.aicoin/wallet.json
aicoin claim      # the free-coin faucet, once per wallet per hour
aicoin show       # address and balance
```

The wallet file **is** the wallet: an Ed25519 seed, written `0600`, with no server-side copy and no
recovery phrase. Back it up (`aicoin export`) or lose the coins.

## Commands

| | |
|---|---|
| `aicoin "<question>"` | ask the panel — the same as `aicoin consortium` |
| `aicoin .` | open a session on this directory (`aicoin session [dir]`) |
| `aicoin single [model]` | one model per question instead of the panel |
| `aicoin multi` | back to the panel |
| `aicoin ais` | which models have been used, what each cost, what each failed |
| `aicoin new [-force]` | create a wallet; refuses to overwrite one without `-force` |
| `aicoin show` | address and balance |
| `aicoin import -key <hex>` | adopt an existing key (a seed, or an expanded private key) |
| `aicoin export [-y]` | print the private key |
| `aicoin balance [address]` | any wallet's balance; yours if you name none |
| `aicoin claim` | take the faucet's grant |
| `aicoin send <address> <amount>` | transfer coins |
| `aicoin token [-days N]` | issue an API token for use elsewhere |
| `aicoin revoke` | invalidate every token issued so far |
| `aicoin ask [-ai p] [-model m] <prompt>` | one model, one answer |
| `aicoin consortium [flags] <prompt>` | every model, then reviewed until nobody objects |
| `aicoin call -ai <p> <path> [-data <json>]` | raw pass-through to a provider's own API |
| `aicoin price` / `aicoin health` | what a coin costs; which providers are live |

Common flags: `-url` (or `$AICOIN_PROXY_URL`, default `https://proxy.aicoin.oeaio.com`) and
`-wallet` (or `$AICOIN_WALLET`, default `~/.aicoin/wallet.json`). Flags may appear anywhere,
including after the prompt.

## Asking

```
$ aicoin ask "explain the balance gate in one sentence"
$ aicoin ask -ai kimi -model kimi-k2.7-code "review this function" < handler.go
```

`ask` sends the provider's own request to the provider's own path — the proxy forwards it untouched
— so `-ai` and `-model` have to agree. With no `-model` it uses each provider's current default.

## The consortium

```
$ aicoin "what breaks first when a proxy meters billing per call?"
$ aicoin -providers anthropic,kimi -rounds 2 -v "review this design" -f design.md
$ aicoin consortium -dir ~/other-project "is anything here unfinished?"
```

Every panelist drafts an answer, an editor merges them, and then the whole panel reviews the result
round after round until one comes back with no comments — or until the round cap, whichever is
first. Every panelist sees the same shared record on every turn: the request, the directory, your
`-context`, the drafts, and every earlier round of comments.

### What the panel can see of your directory

By default it gets a **listing** of the working directory — no contents. A listing is small, and it
is the difference between an answer about how one usually structures a project and an answer about
this one.

| flag | |
|---|---|
| `-f <glob>` | include a file's contents. Repeatable, and comma-separated works too. A bare pattern (`"*.go"`, `main.go`) matches anywhere in the tree; one with a slash (`cli/*.go`) matches the path; a directory (`cli/`) means everything under it. |
| `-dir <path>` | which directory to show. `-dir ""` sends none. |
| `-budget N` | how many characters of directory context to send (default 40,000). |
| `-mode` | `auto` (default), `lead` or `panel` — see below. |
| `-y` | apply proposed file changes without asking first. |

Build output, dependency trees, `.git`, dotfiles and binaries are never sent. Files over 256KB are
skipped, and when the budget runs out the last file is dropped whole rather than cut in half — half
a source file invites confident answers about code the panel cannot see the end of.

Everything here is billed: the record goes to every panelist on every round, so `-f "**"` on a big
repo is a real amount of money.

### Who writes the answer

With a bare question, every model drafts one independently and an editor merges them: their
disagreements are the point, and the merge is where that pays off.

With a directory attached, that stops being worth it — each draft is mostly a re-reading of the
same files, billed once per model, and they converge anyway because the context is doing the work.
So a context-heavy call is **led** by one model: it drafts with everything in front of it, and the
rest of the panel improves its answer round after round. One draft instead of four, and no merge.

The proxy picks between them by how much context you sent; `-mode lead` or `-mode panel` overrides
it. The closing line says which ran:

```
settled — a whole round with no comments | 2 round(s), 7 calls | panel anthropic,openai,kimi, led by anthropic
```

### Making changes, not describing them

A panel that can see your files but cannot touch them answers "create an empty file" with
instructions for typing `touch` yourself. So when a request asks for files to be created, changed
or deleted, the answer comes back as a set of operations, and this CLI shows you what they would do
before doing anything:

```
the panel proposes 2 change(s) in /Users/you/src/my-project:
  create   cmd/serve.go (1284 bytes)
  replace  README.md (2011 bytes, was 1840)
apply? [y/N]
```

- **Nothing is written without a yes.** `-y` (or `/auto` in a session) skips the question for those
  who have decided otherwise; a non-interactive run without `-y` prints the plan and stops, because
  there is nobody to ask.
- **Everything stays inside the directory.** Absolute paths, `..` escapes and the directory itself
  are refused — and one bad path invalidates the whole plan rather than applying the safe half,
  which would leave a state nobody approved.
- **Writes are whole files.** A model asked for a patch invents one against a version it
  half-remembers, so it sends the complete intended contents and this replaces the file. `replace`
  says how many bytes it is losing.
- **An open detail is decided, not asked about.** "Create an empty file" with no name given gets a
  file with a sensible name and a line saying what it chose — a missing filename is a decision to
  make, not a reason to stop and ask.
- **It can run things too.** Ask it to build, test or run something and the plan includes the
  commands, shown verbatim — that is what you are consenting to, so it is never summarised:

  ```
  the panel proposes 3 change(s) in .:
    create   HelloWorld.java (118 bytes)
    run      javac HelloWorld.java
             (compile the Java source)
    run      java HelloWorld
             (run the compiled program)
  apply, and run 2 command(s)? [y/N]
  ```

  Files are written before anything runs, so one block can create a file and then compile it. A
  command that fails stops the ones after it — they were written expecting it to have worked.
  Commands run through `sh` in the working directory with no stdin, and are stopped after five
  minutes. **`-y` covers running as well as writing**: it means letting the panel run shell
  commands on your machine unattended.
- `write`, `delete` and `run` are all there is. The proxy has no filesystem and no shell — the
  acting happens here, on your machine, where the directory is.

A question is still answered with prose; the operations only appear when you asked for a change.

### What it costs, while it happens

While the call runs, the line rewriting itself is your wallet — polled live, so it drops as each
turn settles:

```
context: 34 files listed, 3 included in full, 18432 chars
987 aicoin · $5.43  −13 ($0.07)
```

There is no spinner: what is worth watching during a call that spends money is the money. A
consortium ticks down turn by turn; a single call holds still until it settles at the end.

...and when it lands:

```
settled — a whole round with no comments | 2 round(s), 13 calls | panel anthropic,openai,google,kimi, editor anthropic
◆◆◆◆◇◇◇◇◇◇  15 aicoin spent ($0.08) · 27 left ($0.15)
```

Every coin figure is also given in dollars, at `GET /price` — which is what calls through this
proxy have actually cost on average, not a market rate. There is no market.

The bar is the share of the wallet this call took. It takes minutes, and **every turn is a paid
call** — a four-model panel over two rounds is 13 calls and is billed as 13.

The answer goes to **stdout** and all of the above to stderr, so `aicoin "..." > answer.md` gives
you the answer and nothing else. `-v` prints each round's comments; `-json` prints the proxy's whole
response.

## Secrets

Everything after `$$` on a line is a secret. It never leaves this machine.

```
$ aicoin 'create a .env with STRIPE_KEY set to $$sk-live-9f3aQ7zz'
1 value(s) withheld — the panel sees a reference, not the value
the panel proposes 1 change(s) in .:
  create   .env (36 bytes)
  (1 withheld value(s) put back in on applying)
applied 1 change(s)

$ cat .env
STRIPE_KEY=sk-live-9f3aQ7zz
```

The value is taken out before anything is sent — out of the question, out of the file contents
pulled in with `-f`, out of the session history — and replaced with `{{SECRET_1}}`. The model is
told the reference stands for something it will not be shown, and that writing the reference is how
the value gets where it belongs; this CLI puts the real text back on this side, at the moment the
file is written or the command is run.

So the proxy sees the reference. Every panelist sees the reference. The shared record that four
models read over several rounds has the reference in it. The value exists in one process's memory,
and in the file it was asked to end up in.

- **The plan shows the reference, not the value** — a plan is printed, and scrollback is forever.
  The byte count is the size the file will actually be, since that number gives nothing away.
- **The same value twice is one secret.** Two references would tell the model there are two values,
  which is itself something it does not need to know.
- **Nothing is remembered.** The vault lives for one command or one session, and is never written
  to the stats file, the history, or anywhere else.
- **The marker is literal.** `what does $$ mean in a shell?` withholds the rest of that question —
  the rule fails towards withholding, which is the right direction for a rule about secrets, but it
  is worth knowing before you type it.

## One model, or all of them

A consortium is one paid call per panelist per round. That is the right price for a question worth
reviewing and the wrong one for "create an empty file" — in a large directory it is how a wallet
reaches zero mid-session.

```
$ aicoin single
single mode on — anthropic (carried 37 turns here, 97% of them without failing)
one call per question instead of one per panelist per round. `aicoin multi` to go back.
```

Single mode is the same client with the panel switched off: one call, still grounded in the
directory, still able to propose file changes. The mode sticks until you change it, and
`single` and `multi` do the same thing inside a session.

**Which model it picks is measured, not chosen.** Every consortium response says who was on the
panel, who led it, who reviewed, and who failed at what — so this CLI keeps a running count in
`~/.aicoin/stats.json` and single mode uses whichever model has *carried* the most turns here:
turns completed, with the ones it failed subtracted. Turns that never happened because the wallet
was empty are not held against anyone.

```
$ aicoin ais
model         aicoin       usd   share  carried failed  comments
anthropic        118     $0.65     54%       37      1         9
kimi              54     $0.30     25%       21      6        14
openai            46     $0.25     21%       19      0         4
total            218     $1.20

single mode would use anthropic — carried 37 turns here, 97% of them without failing
```

The coins are exact, not apportioned: the proxy settles each turn against that provider's own
reported usage and returns the breakdown, so this is where the money actually went.

The mode and the record live together in `~/.aicoin/stats.json`, so deleting that file resets both
— you are back in panel mode with nothing measured.

`aicoin single kimi` pins one instead; `aicoin single` with no name goes back to whichever is
carrying the work. What the table does *not* claim is that the top model gives better answers —
whether an answer was good is not in what the proxy reports, so it is not measured here.

## Sessions

```
$ cd ~/src/my-project && aicoin .
aicoin — every question goes to the whole panel, which then reviews its own answer.
directory /Users/you/src/my-project
wallet 00c0759c5748… · 42 aicoin

aicoin ▸ what does the retry logic do?
```

Each question is a full consortium call that can see the directory, and carries the session's
earlier exchanges so follow-ups work. The directory is re-read every turn, so a file you edit
between two questions is seen as it is now. Inside a session:

| | |
|---|---|
| `f <glob>` | include these files' contents from now on (blank clears) |
| `panel <a,b>` | which models sit on the panel (blank for all) |
| `rounds <n>` | cap the review rounds |
| `v` | show or hide each round's comments |
| `files` | what the panel can currently see |
| `balance` | what the wallet holds |
| `single [model]` | one model per question instead of the panel |
| `multi` | back to the panel |
| `ais` | which models have been used and what each cost |
| `auto` | apply proposed changes without asking (off by default) |
| `claim` | take the faucet's grant, when the wallet runs out mid-session |
| `reset` | forget this session's exchanges |
| `exit` | leave — Ctrl-D does too |

**A line that is exactly one of these is a subcommand; everything else is a question.** `single`
switches mode, while *single out the slowest handler* asks the panel — the trailing words are not
something `single` could take, so it stays a question. The same goes for arguments that do not fit:
`rounds 2` is a command, `rounds of review — how many are useful?` is not, and `single gpt7` is a
question because that is not a model this proxy knows.

When you want to settle it yourself:

| | |
|---|---|
| `\single` | a backslash forces a question — ask the panel about the word itself |
| `` `single` `` or `/single` | backticks or a slash force a command, for arguments this would not otherwise recognise (`` `f some odd name` ``) |

The doubt always resolves towards a question, because a question costs a call while a mistaken
command could change the mode or the files under you.

Every question is paid for separately, and the session prints its running total when you leave.

## Paying offline

Load the purse while you have a network, and after that a payment needs nothing from either side:

```
$ aicoin note load 50                     # online, once
7 note(s) worth 50 aicoin are in the purse — they can be paid with no network

$ aicoin note pay 15                      # offline
eyJ2IjoxLCJpZCI6ImE5...  .  Zm9vYmFy...
eyJ2IjoxLCJpZCI6IjE3...  .  YmF6cXV4...

15 aicoin in 2 note(s). Fingerprint(s): 3F-A2-9C 7B-01-D4
The other side should see the same fingerprint(s). These are out of your purse now.
```

The other person, also offline:

```
$ aicoin note accept eyJ2IjoxLCJpZCI6ImE5...
✓ genuine · 10 aicoin · from 00c0759c5748… · 3F-A2-9C
kept 1 note(s) — `aicoin note sync` when you have a network to make them yours
```

The fingerprints match, so both sides know the note arrived as it left. `✓ genuine` means the
ledger's signature checks out against a public key this wallet cached the last time it was online —
no network was used to say that.

```
$ aicoin note sync                        # back online
✓ 3F-A2-9C · 10 aicoin credited
```

| | |
|---|---|
| `note load <amount>` | mint notes and put them in the purse (the only step needing a network) |
| `note list` | what the purse is carrying |
| `note pay <amount>` | hand over notes worth exactly that |
| `note accept <note>` | verify one you were given and keep it |
| `note verify <note>` | check one without keeping it |
| `note sync` | redeem what you accepted |
| `note reclaim` | take back notes nobody accepted |

### Notes made out to somebody

```
$ aicoin note load 20 -for 5f2a91c0…            # while online
4 note(s) worth 20 aicoin, made out to 5f2a91c0… — only they can redeem them,
so handing one to two people leaves the second with nothing rather than a race

$ aicoin note pay 5 -to 5f2a91c0…               # offline, spends the bound ones first
```

and on the other side:

```
✓ genuine · 5 aicoin · from 00c0759c5748… · 3F-A2-9C
   made out to you — nobody else can redeem it, so it cannot have been spent elsewhere
```

This is the one shape of offline payment that **cannot be double-spent at all**. Everything else
here detects a double-spend after the fact; a bound note prevents it, because the second person
holding a copy simply cannot redeem it.

What it costs is foreknowledge: you must know who you are paying before you go offline, and carry
the right denominations for them. So a purse carries both — bound notes for the people you expect
to pay, bearer notes for strangers — and `note list` shows which is which. A note made out to
somebody else is refused at `accept` rather than kept, since it would never redeem.

### Notes that only the person you paid can use

A bearer note is redeemable by whoever holds the string — including whoever photographs it off your
screen while you are paying with it. A claim closes that:

```
# the receiver, first:
$ aicoin note request
5f2a91c0…  9f2c81aa3e04…          # their address, and a nonce nobody else has seen

# the payer:
$ aicoin note load 20 -claimed    # while online
$ aicoin note pay 5 -to 5f2a91c0… -nonce 9f2c81aa3e04…    # offline

# the receiver again, offline:
✓ genuine · 5 aicoin · from 00c0759c5748… · 3F-A2-9C
   claimed for you — signed over to your address against the nonce you gave,
   so a copy of this is no use to anyone else
```

Neither side can make a claim alone. The receiver has a nonce and an address and no way to produce
the payer's signature; the payer has the key and cannot guess a nonce they were never given. So a
payment cannot be prepared for somebody you have not met, and a copy of one is worthless to the
copier.

And if the payer hands the same note to two people, both claims reach the ledger — two signatures by
the same payer naming two different payees, which cannot both be honest. The loser gets the pair
back as proof rather than an apology:

```
✗ 3F-A2-9C · already redeemed by someone else
   proof of double-spend by 00c0759c5748…:
     signed over to 5f2a91c0… (a1b2c3…)
     signed over to 7d4e88f1… (d4e5f6…)
   anyone can check those against the issuer's address; neither could be forged.
```

### What this does and does not promise

- **The coins leave your balance when you load the purse**, not when you hand a note over. That is
  what stops you spending them twice: you no longer have them.
- **Offline, a receiver can tell genuine from forged, and read the amount off the note.** For a
  bearer note they cannot tell whether it has already been given to someone else — that is a fact
  about the ledger, and the ledger is not there. For a note **made out to them**, that question
  does not arise: nobody else can redeem it.
- **Redemption is first-come.** A note handed to two people credits exactly one; the other is told
  `already redeemed`. Every note names its issuer and every step lands in both transaction logs, so
  it is attributable afterwards — not preventable beforehand. Treat a note like cash, because that
  is what it is.
- **Exact change or nothing.** `note pay 3` fails if the purse holds 50/10/5/1, because a note
  cannot be broken in half offline and handing over a 5 would pay more than was owed.
- **A lost note is not lost money**: `note reclaim` returns anything nobody accepted, and notes
  expire (30 days by default) rather than sitting against your balance forever.
- The purse is `~/.aicoin/wallet.purse.json`, mode 0600 — named after its wallet, so two wallets in
  one directory do not share one. Every note in it is spendable by whoever can read the file.
- **What you hand over is kept as a receipt**, not deleted: the issuer needs the string to
  `note reclaim -include-spent` coins a receiver never came back online to redeem.

### Double-spending on purpose

```
$ aicoin note replay FF-05-37 -yes
FF-05-37 · 1 aicoin · replayed — this note was already handed over on 2 Sep 08:41
Whoever redeems it second will be told it was already redeemed, and will have nothing.
```

This exists because the defence needs an attacker to test it. It adds no capability: `note pay`
prints the note to the terminal, so the same string can always be handed to a second person by
scrolling back. What it adds is a repeatable way to *demonstrate* what the system does about it —

```
bob accepts   ✓ genuine · 1 aicoin · FF-05-37        (offline; he cannot tell)
carol accepts ✓ genuine · 1 aicoin · FF-05-37        (offline; nor can she)
bob syncs     ✓ FF-05-37 · 1 aicoin credited
carol syncs   ✗ FF-05-37 · already redeemed by someone else
```

— which is the honest shape of an offline bearer instrument: nobody can tell at hand-off, exactly
one person ends up with the coins, and the loser is told plainly rather than left wondering.

## Tokens

`aicoin token` issues one for other tools; `ask`, `call` and `consortium` don't need it — they mint
a one-hour token per call from the wallet key, which expires on its own.

A token can spend the wallet's coins on AI calls but cannot transfer them, so a leaked token cannot
drain a wallet. `aicoin revoke` invalidates every token issued so far, including the ad-hoc ones.
