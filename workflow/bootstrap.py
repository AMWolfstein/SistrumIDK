"""Create missing project support/history from portable defaults; never import task data."""
from __future__ import annotations
import json
import os
from pathlib import Path
import subprocess
import registry

ENTRY = '\n\n<!-- workflow-bootstrap -->\n## Shared workflow instructions\n\nBefore modifying this repository, read and follow [workflow/README.md](workflow/README.md)\nand its linked policy/skills. This applies to every agent, including small changes.\n'


def create(root,relative,data,mode=0o644):
    """Exclusive creation preserves files that appear between checking and opening."""
    target=registry.safe(root,relative);target.parent.mkdir(parents=True,exist_ok=True)
    try:
        with target.open('xb') as out:out.write(data);out.flush();os.fsync(out.fileno())
    except FileExistsError:
        if not target.is_file():raise ValueError(f'Expected regular file, found another object: {relative}')
        return False
    target.chmod(mode);return True


def allowed(relative):
    parts=Path(relative).parts
    if relative in {'CLAUDE.md','.github/workflows/workflow-integrity.yml'}:return True
    if parts[:2] in [('.agents','skills'),('.agents','reviewers')]:return Path(relative).suffix=='.md'
    if parts[:1]!=('.specify',):return False
    if len(parts)==2:return parts[1] in {'.gitignore','project-policy.md','init-options.json','integration.json','upstream.json','LICENSE.spec-kit'}
    return parts[1] in {'scripts','templates','memory','integrations','workflows'}


def project_data(root):
    history=registry.safe(root,registry.HISTORY);statefile=registry.safe(root,registry.REGISTRY)
    existed=history.exists();created=False
    if existed and not history.is_file():raise ValueError('History path is not a regular file')
    try:
        if statefile.exists():
            state=registry.load(root);registry.record_rows(root,state)
            if not existed:created=create(root,registry.HISTORY,registry.history(state).encode('utf-8'))
            if history.read_text(encoding='utf-8')!=registry.history(state):
                return {'status':'INCOMPLETE','history':'preserved' if not created else 'created','reason':'Existing history differs from local registry; not overwritten. Reconcile explicitly.'}
            return {'status':'PASS','history':'created' if created else 'preserved','registry':'preserved'}
        records=any((root/'specs').glob('*/tasks.md')) or any((root/'.specify/quick-tasks').glob('TOR*.md'))
        blank=registry.history(registry.empty())
        if not existed:created=create(root,registry.HISTORY,blank.encode('utf-8'))
        if records or history.read_text(encoding='utf-8')!=blank:
            return {'status':'INCOMPLETE','history':'created' if created else 'preserved','reason':'Existing project records/history need explicit init --migrate/reconciliation; setup does not reset or import them'}
        initialized=create(root,registry.REGISTRY,(json.dumps(registry.empty(),indent=2)+'\n').encode('utf-8'))
        return {'status':'PASS','history':'created' if created else 'preserved','registry':'created' if initialized else 'preserved'}
    except (ValueError,OSError,KeyError,TypeError) as error:
        return {'status':'INCOMPLETE','history':'preserved' if existed else 'not-created','reason':str(error)}


def installation_assets(assets, target=None):
    manifest = json.loads(Path(__file__).with_name('install-manifest.json').read_text())
    if target is not None and target not in manifest['targets']:
        raise ValueError(f'Unknown setup target: {target}')
    groups = [(assets, manifest['shared'])]
    if target:
        groups.append((registry.safe(assets, f'adapters/{target}'), manifest['targets'][target]))
    files = {}
    for folder, entries in groups:
        for entry in entries:
            source = registry.safe(folder, entry)
            candidates = sorted(source.rglob('*')) if source.is_dir() else [source]
            for candidate in candidates:
                if candidate.is_symlink():
                    raise ValueError(f'Symlink in portable assets: {candidate}')
                relative = candidate.relative_to(folder).as_posix()
                if candidate.is_file() and allowed(relative):
                    files[relative] = candidate
    return files


def preview(root, files):
    paths = set(files) | {'AGENTS.md', registry.HISTORY, registry.REGISTRY}
    create_paths, preserve_paths = [], []
    for relative in sorted(paths):
        destination = registry.safe(root, relative)
        if destination.exists() and not destination.is_file():
            raise ValueError(f'Expected regular file: {relative}')
        (preserve_paths if destination.exists() else create_paths).append(relative)
    agents = root / 'AGENTS.md'
    update_paths = []
    if agents.is_file():
        text = agents.read_text(encoding='utf-8')
        if 'workflow/README.md' not in text and '<!-- workflow-bootstrap -->' not in text:
            preserve_paths.remove('AGENTS.md')
            update_paths.append('AGENTS.md')
    ignored = subprocess.run(['git', 'check-ignore', '-q', '.work/workflow-probe'], cwd=root)
    if ignored.returncode == 1:
        ignore = registry.safe(root, '.gitignore')
        (update_paths if ignore.exists() else create_paths).append('.gitignore')
    elif ignored.returncode != 0:
        raise ValueError('Cannot inspect Git ignore rules')
    return {'status': 'PASS', 'dry_run': True, 'create': sorted(create_paths),
            'update': sorted(update_paths), 'preserve': preserve_paths}


def prepare(root,assets=None,*,target=None,dry_run=False):
    """Install defaults only when missing; preserve custom project configuration."""
    top=subprocess.check_output(['git','rev-parse','--show-toplevel'],cwd=root,text=True).strip()
    if Path(top).resolve()!=root.resolve():raise ValueError('Setup target must be the Git repository root')
    if subprocess.check_output(['git','ls-files','-z','--','.work'],cwd=root):raise ValueError('Refusing setup with tracked .work files')
    assets=assets if assets is not None else Path(__file__).with_name('assets')
    if not assets.is_dir():raise ValueError('Portable workflow assets are missing; copy the complete workflow/ directory')
    files = installation_assets(assets, target)
    if dry_run:
        return preview(root, files)
    installed=[];preserved=[]
    with registry.lock(root):
        ignored=subprocess.run(['git','check-ignore','-q','.work/bootstrap-probe'],cwd=root)
        if ignored.returncode not in (0,1):raise ValueError('Cannot inspect Git ignore rules')
        if ignored.returncode:
            ignore=registry.safe(root,'.gitignore')
            prefix=ignore.read_bytes() if ignore.exists() else b''
            with ignore.open('ab') as out:out.write((b'\n' if prefix and not prefix.endswith(b'\n') else b'')+b'\n# Local workflow caches\n.work/\n')
        for relative, source in sorted(files.items()):
            if create(root,relative,source.read_bytes(),source.stat().st_mode & 0o777):installed.append(relative)
            else:preserved.append(relative)
        agents=registry.safe(root,'AGENTS.md')
        if not agents.exists():create(root,'AGENTS.md',('# Project instructions\n'+ENTRY).encode('utf-8'))
        else:
            text=agents.read_text(encoding='utf-8')
            if 'workflow/README.md' not in text and '<!-- workflow-bootstrap -->' not in text:
                with agents.open('ab') as out:out.write(ENTRY.encode('utf-8'))
        project=project_data(root)
    return {'status':project['status'],'installed_support_files':installed,'preserved_support_files':preserved,'project':project,
            'note':'Existing files are never replaced; review preserved custom instructions for compatibility. Project task data is not copied from assets.'}
