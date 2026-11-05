/** Keyboard boundary for the public navigation overlay; never changes account state. */
export function bindMobileNavigation(
  toggle: HTMLButtonElement,
  menu: HTMLElement,
  background: HTMLElement[],
  close: () => void,
) {
  const owner = toggle.ownerDocument;
  const overflow = owner.body.style.overflow;
  const previousInert = background.map((element) => ({ element, inert: element.inert }));
  owner.body.style.overflow = "hidden";
  background.forEach((element) => { element.inert = true; });
  const controls = () => [toggle, ...menu.querySelectorAll<HTMLAnchorElement>("a[href]")];
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      toggle.focus();
    } else if (event.key === "Tab") {
      const items = controls();
      const index = items.indexOf(owner.activeElement as HTMLButtonElement);
      if (index < 0 || (event.shiftKey && index === 0) || (!event.shiftKey && index === items.length - 1)) {
        event.preventDefault();
        items[event.shiftKey ? items.length - 1 : 0]?.focus();
      }
    }
  };
  owner.addEventListener("keydown", onKeyDown);
  return () => {
    owner.removeEventListener("keydown", onKeyDown);
    owner.body.style.overflow = overflow;
    previousInert.forEach(({ element, inert }) => { element.inert = inert; });
  };
}
