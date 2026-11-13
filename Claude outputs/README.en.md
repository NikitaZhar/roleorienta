# roleorienta

**An app that finds employers for you, collects their jobs from every channel they use, and brings you only the ones that fit — in one place, without duplicates.**

roleorienta discovers the companies of a country, finds where each of them publishes jobs — the state job portal, the big job board, the company's own recruiting system or career page — and collects those jobs continuously. You describe what you are looking for once; new matching jobs arrive every hour, and you keep track of your applications in the same interface.

**Status (November 2026): the MVP is complete.** It runs for Slovakia, the first market, for an administrator and a user on one machine. The next stage is moving to a server: a 14-day continuous run on live sources, then container images and hosting. Features marked *planned* below are part of the roadmap, not of the current version.

Next to this description sits a visual presentation page (`roleorienta-en.html`, also as `roleorienta-en.pdf`) that shows the same at a glance.

---

## The problem

In many job markets a meaningful share of interesting jobs never reaches the big job boards. Large employers publish them in their own recruiting systems (Workday, SAP SuccessFactors and others) or on their career pages; others publish only on the state job portal. For the candidate this is a blind spot: jobs that fit exist, but they aren't visible where you're used to looking.

Checking dozens of employer sites by hand is hard to keep up, and the boards that do exist repeat the same job several times and mix in agencies and stale listings.

---

## Who it's for

roleorienta suits an **active candidate** searching in a specific profession and country who wants to:

- see every job that fits, including those published only in an employer's own recruiting system;
- get new jobs automatically instead of repeating the same search;
- keep track of applications and interviews in one place, not in spreadsheets and bookmarks.

The profession and the market are set by the user's conditions — the product isn't tied to them. A second role is the **administrator**, who watches the collection, fills the gaps the automation can't close (a company whose career page wasn't found, a profession title the app doesn't recognize) and maintains the position dictionary.

---

## How it works today

1. **Describe what you're looking for.** Choose countries, work format (any, office, remote — hybrid jobs fit any format) and positions. Positions are professions from the international ISCO-08 / ESCO classification (for example *Software developers* or *Systems analysts*) and precise positions from the dictionary (*Java developer*, *DevOps engineer*). Suggestions understand English and Slovak profession titles; the interface is in English and Russian. All matching jobs that were already collected appear right away, not on a blank screen.
2. **Get new jobs every hour.** Collection runs continuously; every hour new matching jobs are added to your results. Jobs that arrived since your last visit are marked, and one click shows only them.
3. **Work through the results.** Search the list by employer or position, narrow it to one of your positions, open the original posting in one click. Mark a job ✓ *Applied* or × *Not interested* right in the row — with a few seconds to undo.
4. **Track your applications.** *My applications* groups jobs by state: active (*Applied*, *Interview*), *No reply for 3+ weeks*, and *Completed* (*Employer declined*, closed by the employer, or no reply for 6+ weeks). Every status change is kept in the job's history. *Not interested* keeps dismissed jobs, and any of them can be returned to the results.
5. **Check a company.** In *Companies* you can find any company and see whether its jobs are collected. If its career page wasn't found, you can add the address yourself — the app checks it and starts reading it.

The app doesn't send applications or messages to employers — you always apply yourself.

---

## Where the jobs come from

- **The state job portal** (Služby zamestnanosti in Slovakia), together with the job board ads (profesia.sk, kariera.sk) that the portal links to an employer's registration number.
- **profesia.sk directly** — ads that aren't on the portal are added from the board's own pages.
- **Employers' own recruiting systems** — Workday, SAP SuccessFactors, Oracle Taleo, Oracle Recruiting Cloud, Greenhouse, SmartRecruiters, Personio, Phenom, Nalgoo, and sites with schema.org `JobPosting` markup. The app finds them on its own: from the company register through the company's site to its careers section, and from the "apply" button of a board ad to the employer's system.
- **Companies' own career pages** without a recruiting system are read as they are (a simpler reader; in the MVP it isn't developed further).

The same job found in several channels is **one record**: the app joins postings by the ad number, the address, the recruiting system's job id, or the same title and place at the same employer.

---

## What you see for a job

Position, employer, country and work format; the date the job was opened (the employer's publication date, or the date the app found it); language requirements where the text states them; a mark when the employer closed the job; and a link to the original posting.

Principles:

- **No guesses.** If something isn't known, it isn't shown — no placeholder that looks like data.
- **A failed check doesn't close a job.** A source that is down or read only partly leaves its jobs open; a job closes only after it is missing from several complete reads in a row, or from every channel it was found in.
- **Old jobs step aside.** Jobs the employer published more than 4 months ago aren't shown in results.

---

## For the administrator

A separate `/admin` page: whether collection is running right now, daily metrics, a queue of companies whose jobs are being lost (one at a time, with a browser notification), searches users made that found nothing, profession titles from profesia.sk that have no group yet (with suggested groups), the position dictionary, and companies and career pages added by users.

---

## Planned

- **Email digest of new jobs** — built, with frequency in *Settings*; sending is switched on together with the server.
- **Company monitoring** — pick the employers you care about and follow their jobs.
- **Change history for the user** — how requirements, salary and terms of a job changed (the history is already recorded internally).
- **Salary as in the job** — amount or range, currency, period, before or after tax, and a lower-bound filter that compares only comparable figures.
- **Skills and employer technology profile** — the technologies a job asks for, matched against your own, and what the employer asks for most often.
- **Application details** — a copy of the job as it was when you applied, hiring stages, notes, dates and interview outcomes; side-by-side comparison of saved jobs; in-app notifications.
- **More countries**, and excluding recruitment agencies' jobs from every channel.

---

## Coverage boundaries

We don't promise every job on the market, and we say so plainly: coverage grows with the sources and formats the app can read. Collection uses only open pages and permitted sources, follows robots.txt and per-site limits; getting around access restrictions and CAPTCHAs is not part of the product. Automatic sending of applications is not supported — the decision and the action are always the candidate's.

---

*This is a description of the app's capabilities. Illustrations on the presentation page are schematic; company names and dates are shown as examples.*
