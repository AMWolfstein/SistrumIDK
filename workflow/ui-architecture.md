# Shared Compose UI architecture

Rule: **One design system — one implementation per shared component — screen-specific
content only.** This governs future UI work; this workflow task implements none of it.

## Before creating anything

Search for an existing implementation first, in this order:

```sh
python3 workflow/codegraph/code_tools.py query --symbol <Name>
python3 workflow/codegraph/code_tools.py query --file <path>
```

`is_composable` and `decorators` on function nodes expose `@Composable`, `@Preview`
and similar annotations for reuse discovery. Prefer composition and Slot APIs over
inheritance and flag-heavy components.

## Shared inventory to consolidate

App Shell, Screen Scaffold, Shared Header, Shared Scroll Behavior, Shared Backdrop
Management, Shared Glass Renderer, Shared Progressive Blur, Shared Navigation,
Shared Mini Player, Shared Details Layout, Shared Collection Layout, Shared Settings
Layout, Shared Player Layout, reusable controls and content components.

Artist, Album, Playlist, Genre and Composer screens reuse the shared details layout
where behaviour is genuinely equivalent. Library collections reuse list, grid,
sorting, selection and navigation behaviour. Screen-specific content, actions, data
and business logic stay independent. Buttons differing only by icon, state or action
share one component.

## File organization

- Keep small private helpers in their owning file.
- Extract independently reusable components into their own files.
- Do not split files by size alone, add forwarding wrappers, build giant universal
  components with many flags, or over-abstract.
- Preserve clear ownership and dependency direction.

## Runtime constraints

Shared rendering infrastructure must not imply one oversized backdrop capture or
whole-screen rendering. Minimise duplication without sacrificing performance or
clarity.

## Verification expectations

UI changes require the affected module's compile/lint/test tasks and a reviewer check
for duplicate components. A screenshot or manual run is evidence only when stated as
such; it is not a substitute for compilation.
