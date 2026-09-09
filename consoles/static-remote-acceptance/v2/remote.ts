export function mount(container: HTMLElement): void {
  const output = document.createElement('p');
  output.className = 'sf-static-remote-v2';
  output.textContent = 'Remote v2 executed';
  container.append(output);
}
