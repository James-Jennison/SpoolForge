# 3D Filament Profiles integration (future authorized provider only)

**Pivot status:** 3D Filament Profiles is a competitive reference, not a required data source. The provider contract below applies only if a supported licensed API/export becomes available.

## Current boundary

3D Filament Profiles is a strong complementary source for SpoolForge. Its
public catalog includes brand, material, type, color, RGB values, temperature
ranges, retailer/manufacturer links, and community Transmission Distance data.
It also exports selected user spools as Bambu Studio or OrcaSlicer profiles.

The service does not currently publish a supported data API or licensed bulk
snapshot. The site owner has stated that scraping is against the service terms.
SpoolForge must therefore not use the site's internal React Server Component
payloads, browser cookies, or unofficial extraction scripts as a product data
feed.

The application currently provides a compliant bridge: **Search 3D Filament
Profiles** opens the public catalog in the user's browser from both a saved
filament and the AI label review form. No values are imported automatically.

## Authorized integration contract

When 3D Filament Profiles publishes an API, JSON export, or licensed snapshot,
add it behind a `FilamentProfileProvider` boundary. A provider candidate must
carry:

- stable upstream filament identifier and canonical public URL;
- brand, material, material type/product line, color name, and RGB value;
- nozzle and bed temperature ranges;
- optional diameter, package mass, SKU/GTIN, TD, flow, fan, and retailer links;
- upstream revision or retrieval timestamp;
- source name, license/usage grant, and record URL;
- field-level origin for every non-empty value.

Matching should rank exact GTIN/SKU first, then normalized brand + material +
product/type + color + diameter. A returned match remains a review candidate.
It must never silently replace AI label values, local edits, or a more specific
package record. The owner selects the candidate and the fields to copy.

## Precedence and persistence

For each field, SpoolForge keeps both the value and source. Precedence is:

1. explicit local edit;
2. exact package evidence from a label, GTIN, or SKU;
3. user-approved 3D Filament Profiles candidate;
4. existing catalog default;
5. generic material fallback.

Refreshing the upstream source may propose changes but cannot overwrite local
values. Removing or disabling the provider leaves all accepted local records
usable offline and retains the last accepted provenance.

## Provider acceptance gates

Before enabling automatic lookup, obtain written permission or published terms
that allow application use, caching, and redistribution of the required fields.
Then verify:

- documented authentication and rate limits;
- stable IDs and pagination;
- lookup by GTIN/SKU or bounded search fields;
- license/attribution and deletion requirements;
- deterministic field mapping with fixtures;
- malformed, partial, duplicate, discontinued, and rate-limited responses;
- no credentials or authenticated browser state in the Android application;
- review-only UI and field-level provenance through save, edit, export, and NFC
  encoding.

## References

- https://3dfilamentprofiles.com/filaments
- https://3dfilamentprofiles.com/help/slicer-profiles
- https://github.com/MarksMakerSpace/filament-profiles
- https://www.reddit.com/r/3dfilamentprofiles/comments/1tj86u3/may_i_ask_how_to_apply_for_api_for_personal_use/
