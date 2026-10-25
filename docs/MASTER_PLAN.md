# pho.psd — Product Master Plan

## Mission
Create a professional Photoshop-class *long-term* photo editor built expressly for Android tablets. Initially deliver a genuinely useful, responsive editor for photographic compositing: users import images, stack layers, transform, mask, adjust, save editable projects, reopen them, and export correct photographs. Build a stable core before implementing the full desktop Photoshop feature universe.

## Users & core jobs
- Landscape, concert and portrait photographers who adjust color/exposure and retain detail.
- Photomanipulators making convincing compositions from multiple photos using masks, selections and blending.
- Retouchers removing flaws on separate layers and preserving originals.
- Creators working on the go with stylus, fingers, keyboard or mouse.

## Product constraints
- Branding: **pho.psd**, lowercase, period included. Original identity; never imply affiliation with Adobe.
- Exclusively Android tablets; landscape primary. No phone-first navigation or stretched mobile controls.
- Native, offline-capable; no required subscription, login or cloud processing for essential operations.
- Modern charcoal/blue visual style based on `design-reference.png`.
- Full Photoshop-scale *roadmap*, but implement transparent, reliable subsets rather than nonfunctional placeholders.
- Accessibility: scalable type, touch targets, contrast, semantics, external input and sensible focus order.

## Non-negotiable first usable editing session
1. Create or import a JPEG/PNG document at native resolution.
2. Add a second image as a separate layer, rename/reorder/show/hide it and alter opacity.
3. Move/scale layer and composite with proper alpha handling.
4. Edit a raster mask with a soft brush and undo the stroke.
5. Add at least exposure and curves as editable non-destructive adjustments.
6. Save to a native `.phopsd` project, quit, reopen, and preserve layers, mask and adjustments.
7. Export a correctly rendered full-resolution PNG or JPEG, without corrupting the source files.

The roadmap specifies progressive acceptance gates: do not pretend all seven are available in the first scaffold.

## UX intent
Easy to discover essentials, difficult to outgrow. Central canvas dominates; compact left tool rail; authentic Layers/Channels/Paths tabs on right; full menu bar across top with File/Edit/Image/Layer/Select/Filter/View/Window/Help; separate quick-action strip Edit/Adjust/FX/Filters/Layer/Select; contextual bottom tool bar. No redundant giant buttons or trendy decorative dashboards.

## Major planned capability groups
- Raster editing, brush/eraser, selection, transform/crop, masks, groups and blend modes.
- Non-destructive photographic adjustments (exposure, levels, RGB curves, hue/saturation, temperature, shadows/highlights, etc.).
- Retouching (clone/heal/patch), histogram, brushes, rulers/guides, navigator and histories.
- Gradients, text, vector paths, layer styles, filters and later advanced warp/liquify.
- Accurate ICC-managed sRGB/P3 and later higher bit-depth / working-space support.
- Import/export JPEG, PNG and WebP first; later TIFF/HEIC/AVIF/RAW/PSD/PSB with truthful feature matrices.
- Potential on-device AI-assisted selection/segmentation later; core functions remain offline and independent.

## Product acceptance standards
A feature is 'done' only if UI, backend operation, state, undo where appropriate, save/reopen where appropriate, final export correctness, and tests are functional. Measure real performance; don't fabricate benchmark figures. Give clear statuses for unfinished features.

## Not in initial MVP
Full PSD/PSB round-tripping, all Photoshop brush dynamics, complex AI content aware fill, all RAW cameras, CMYK/LAB/16-bit professional fidelity, Smart Objects, advanced text engine and arbitrary plug-in ecosystem.
