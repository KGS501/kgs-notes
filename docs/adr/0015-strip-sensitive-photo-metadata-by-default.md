---
status: superseded by ADR-0022
---

# Strip sensitive photo metadata by default

When a photo enters KGS Notes as a Managed Attachment, the default import path will remove location, device, and other non-rendering metadata while preserving the pixels, orientation, and color information required for faithful display. The attachment flow offers an explicit **Keep original** option before import. This reduces accidental disclosure when notes synchronize or are exported, at the cost of making the default attachment differ from the original camera file.
