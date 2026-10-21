const components = [
  {name: 'Frontend', stack: 'Next.js · React · TypeScript', detail: 'Static export scaffold. Product screens are planned.'},
  {name: 'Backend', stack: 'Java 21 · Spring Boot · PostgreSQL', detail: 'Application entry point, protected status API, health check and migration baseline.'},
  {name: 'System', stack: 'Docker Compose · GitHub Actions', detail: 'Local containers, build pipeline and an Ubuntu VPS deployment template.'},
];

export default function Home() {
  return <main>
    <header><a href="/" className="brand">↗ loadpilot</a><span className="badge">PROJECT SETUP</span></header>
    <section className="intro"><p className="eyebrow">THESIS / PERFORMANCE TESTING PLATFORM</p>
      <h1>A foundation for<br/>better measurements.</h1>
      <p className="lead">The workspace is ready for staged development. Start with the foundation, then build targets, scenarios and controlled k6 experiments.</p>
    </section>
    <section className="components" aria-label="Project components">{components.map((item, index) =>
      <article key={item.name}><span className="number">0{index + 1}</span><h2>{item.name}</h2><p className="stack">{item.stack}</p><p>{item.detail}</p></article>)}</section>
    <section className="next"><div><p className="eyebrow">NEXT STEP</p><h2>Review the staged roadmap.</h2></div>
      <p>See <code>DEVELOPMENT_ROADMAP.md</code> for backend, frontend and system work at every stage. No testing workflows or AI features are implemented yet.</p></section>
    <footer><span>Loadpilot · v0.1.0 scaffold</span><span>Next.js / Java / AI / k6</span></footer>
  </main>;
}
