# Make archive imports additive

Archive Import into a non-empty installation will never overwrite an existing note. It previews the incoming content, preserves archived identities when they are unused, and generates new identities on collision while rewriting Note Links within the imported set. This preserves both libraries under ambiguous provenance at the cost of producing new identities rather than treating an archive as an unquestioned whole-app replacement.
