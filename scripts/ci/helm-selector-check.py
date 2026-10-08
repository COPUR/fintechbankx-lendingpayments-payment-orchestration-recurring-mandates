#!/usr/bin/env python3
"""Selector checks on the rendered chart (read from stdin).

The mesh NetworkPolicies give datastore egress (Aurora 5432) to pods selected
by app.kubernetes.io/name only, so the Flyway hook Job pod must carry
app.kubernetes.io/name = the service account name. The component label then
keeps it out of everything that selects the API pods: the Service, the PDB,
the chart's NetworkPolicy, the Deployment selector and its spread constraints.

Usage: helm template ... | python3 scripts/ci/helm-selector-check.py <service account>
"""
import sys

import yaml

NAME = "app.kubernetes.io/name"
COMPONENT = "app.kubernetes.io/component"


def fail(message):
    print(f"helm-selector-check: {message}", file=sys.stderr)
    sys.exit(1)


def matches(selector, labels):
    return all(labels.get(key) == value for key, value in selector.items())


def main():
    if len(sys.argv) != 2:
        fail("usage: helm-selector-check.py <service account>")
    service_account = sys.argv[1]
    docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
    by_kind = {}
    for doc in docs:
        by_kind.setdefault(doc["kind"], []).append(doc)

    deployments = by_kind.get("Deployment", [])
    jobs = by_kind.get("Job", [])
    if len(deployments) != 1 or len(jobs) != 1:
        fail(f"expected one Deployment and one Job, got {len(deployments)} and {len(jobs)}")
    deployment, job = deployments[0], jobs[0]
    api_pod = deployment["spec"]["template"]["metadata"]["labels"]
    job_pod = job["spec"]["template"]["metadata"]["labels"]

    if job_pod.get(NAME) != service_account:
        fail(f"Job pod {NAME} is {job_pod.get(NAME)!r}, the mesh Aurora egress policy selects {service_account!r}")
    if job_pod.get(COMPONENT) != "db-migration":
        fail(f"Job pod {COMPONENT} must be db-migration, got {job_pod.get(COMPONENT)!r}")
    if api_pod.get(NAME) != service_account or api_pod.get(COMPONENT) != "api":
        fail(f"Deployment pods need {NAME}={service_account} and {COMPONENT}=api")

    selectors = [("Deployment selector", deployment["spec"]["selector"]["matchLabels"])]
    for i, spread in enumerate(deployment["spec"]["template"]["spec"].get("topologySpreadConstraints", [])):
        selectors.append((f"Deployment topologySpreadConstraints[{i}]", spread["labelSelector"]["matchLabels"]))
    for svc in by_kind.get("Service", []):
        selectors.append((f"Service {svc['metadata']['name']}", svc["spec"]["selector"]))
    for pdb in by_kind.get("PodDisruptionBudget", []):
        selectors.append((f"PodDisruptionBudget {pdb['metadata']['name']}", pdb["spec"]["selector"]["matchLabels"]))
    for policy in by_kind.get("NetworkPolicy", []):
        selectors.append((f"NetworkPolicy {policy['metadata']['name']}", policy["spec"]["podSelector"]["matchLabels"]))
    if not any(label.startswith("Service ") for label, _ in selectors) or not by_kind.get("PodDisruptionBudget"):
        fail("expected a Service and a PodDisruptionBudget in the render")

    for label, selector in selectors:
        if not selector:
            fail(f"{label} is empty and would select every pod")
        if not matches(selector, api_pod):
            fail(f"{label} {selector} does not select the API pods")
        if matches(selector, job_pod):
            fail(f"{label} {selector} also selects the db-migration Job pod")

    for hpa in by_kind.get("HorizontalPodAutoscaler", []):
        target = hpa["spec"]["scaleTargetRef"]
        if target["kind"] != "Deployment" or target["name"] != deployment["metadata"]["name"]:
            fail(f"HorizontalPodAutoscaler targets {target}, not the Deployment")

    print(f"helm-selector-check: {len(selectors)} selectors match the API pods and not the db-migration Job pod")


if __name__ == "__main__":
    main()
