# Amazon catalog enrichment (superseded research)

**Status:** superseded by [ADR-001](decisions/ADR-001-portable-filament-identity-pivot.md). Rainforest/Amazon enrichment is not part of the focused product or current runtime. This document remains as research history.

## Role in SpoolForge

Amazon is an optional, online enrichment source after the offline catalog and
deterministic barcode lookup paths fail or return ambiguous candidates. It does
not replace OFD, OpenPrintTag, SpoolmanDB-Community, The Filament Database, or
the user's saved local records.

The preferred lookup flow is:

1. Decode GTIN, QR, and other machine-readable values locally.
2. Search the merged offline index by valid GTIN and manufacturer SKU.
3. If unresolved, extract visible brand, product, material, color, diameter,
   mass, and temperatures from the label image.
4. Search Amazon with those human-readable fields through a server-side catalog
   integration.
5. Show candidate Amazon products with title, image, variation, and ASIN. The
   user selects a match before any Amazon value is merged into a local record.
6. Store each accepted field with Amazon marketplace, ASIN, retrieval time, and
   user-confirmation provenance.

## Identifier boundary

- ASIN is Amazon's catalog identifier.
- UPC, EAN, and GTIN remain retail identifiers and are normalized separately.
- Manufacturer SKU remains manufacturer-scoped.
- Seller SKU remains seller-scoped.
- An `X00[A-Z0-9]{7}` value is only an Amazon-style identifier candidate. Its
  shape does not prove that it is an ASIN, FNSKU, retail GTIN, or manufacturer
  SKU. SpoolForge retains the exact value as evidence and requires confirmation.

## Automatic route without seller or affiliate credentials

Opening an Amazon search and asking the user to bring a product back into the
app is not an acceptable lookup flow. The prototype should use a server-side
Amazon product-data provider so an unresolved scan remains automatic.

The preferred prototype provider is **Rainforest API** because it supports all
three retrieval paths SpoolForge needs:

1. A valid printed GTIN/EAN/UPC can be submitted directly for a GTIN-to-ASIN
   product lookup.
2. A known ASIN can retrieve structured product details.
3. Brand, product, material, color, diameter, mass, manufacturer SKU, and an
   Amazon-style identifier candidate can be submitted as Amazon search terms.

The request belongs in SpoolForge's backend service. The APK sends normalized
label evidence and receives a small list of normalized candidates. The API key
must never be packaged in the Android app.

Rainforest is a third-party Amazon data service and describes its results as
web-scraped public data; it is not an Amazon API or Amazon endorsement. Its free
trial is sufficient for a small acceptance set, while continued use requires a
paid plan. This dependency must therefore remain behind an `AmazonCatalogProvider`
adapter so another provider can replace it without changing matching or UI code.

**SerpApi** is the fallback prototype adapter. It has a larger free allowance
and provides structured Amazon keyword-search and ASIN-product results, but its
documented Amazon endpoints do not offer Rainforest's direct GTIN-to-ASIN input.
That makes SerpApi less suitable as the primary resolver for scanned retail
barcodes.

Current provider documentation:

- https://docs.trajectdata.com/rainforestapi/product-data-api/reference/gtin-upc-ean-to-asin
- https://docs.trajectdata.com/rainforestapi/search-product
- https://trajectdata.com/pricing/rainforest-api
- https://serpapi.com/amazon-search-api
- https://serpapi.com/amazon-product-api
- https://serpapi.com/pricing

For either provider, SpoolForge ranks returned candidates using exact GTIN,
exact manufacturer SKU, brand, material, color, diameter, and mass. It displays
the best candidates with match reasons and requires selection before adding
Amazon-derived values to a local record. No first-result auto-merge is allowed.

## Optional integration routes

### Third-party provider fallback

Keepa can retrieve Amazon products by ASIN or product code and search by keyword,
but its main value is price history rather than SpoolForge's scan-to-catalog use
case. Oxylabs supports parsed Amazon search and product requests, but is designed
for larger scraping workloads. Both remain replacement candidates behind the
same provider interface rather than prototype dependencies.

### Amazon Creators API

Use only when the operator has Creators API eligibility. `SearchItems` provides
brand/title/keyword discovery and `GetItems` retrieves a known ASIN. Credentials
stay on a backend service and never ship in the APK.
Amazon currently requires enrollment in the target marketplace's Associates
program, Creators API registration, and qualifying-sales eligibility.

Official documentation:

- https://affiliate-program.amazon.com/creatorsapi/docs/
- https://affiliate-program.amazon.com/creatorsapi/docs/en-us/api-reference/operations/search-items
- https://affiliate-program.amazon.com/creatorsapi/docs/en-us/get-started/using-curl

### Selling Partner API Catalog Items

This route is not part of the baseline because the owner is not an Amazon seller.
Use it only if SpoolForge later has an authorized seller/developer relationship
that fits Amazon's SP-API requirements. Catalog Items can search Amazon's catalog using
ASIN and retail identifier types including UPC, EAN, and GTIN. It must not be
treated as an anonymous public product database.

Official documentation:

- https://developer-docs.amazon.com/sp-api/docs/catalog-items-api-v2022-04-01-reference

## Product requirements

- Amazon lookup is opt-in and visibly online.
- Results are marketplace-specific.
- Candidate selection is explicit; no first-result auto-match.
- The UI shows why each candidate matched.
- Amazon values never silently override printed-label or deterministic-code
  evidence.
- Cached records retain their source and retrieval timestamp.
- Credentials, request signing, quotas, and API policy enforcement live on the
  server side.
- Do not scrape Amazon retail pages from the Android client.
