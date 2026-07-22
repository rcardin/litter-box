# Project conventions

This file is yours. litter-box splices it verbatim into every prompt it sends, as
`{{CONVENTIONS}}`. Everything the agent needs to know that is true of THIS project and not of
software in general goes here. The protocol — one iteration, no pushing, a test per acceptance
criterion, who reports success — is not here: it ships with litter-box and you cannot break it
by editing this file.

Delete the prompts below as you answer them. An empty file is valid and the loop will run; it
will just produce code that matches nothing in particular.

## Layout

Where does code go? Name the directories and what belongs in each. If the project has a layering
rule (what may import what), state it as a rule, not as a description.

## The template to copy

Point at one existing feature, by path, that is the shape you want new work to take. This is the
single highest-value line in this file. An agent copying a real slice of your codebase beats any
amount of prose about style.

## Test tiers

litter-box runs a fast gate every iteration and lets CI run everything else. Say which directory
holds which. Say what a test is not allowed to need in order to live in the fast tier: a database,
a container, the network, a credential.

## Build and lint rules

Anything that turns a warning into a failure, any formatter that must be run, any check that is
not part of the gate command but will fail CI.

## Anything that has bitten you

Rules that exist because something went wrong once. Say why, briefly. An agent that knows the
reason applies the rule to cases you did not list.
