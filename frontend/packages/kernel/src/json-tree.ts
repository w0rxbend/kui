/**
 * The JSON tree has its own entry so feature packages can lazy-load it without pulling the record
 * viewer's heaviest renderer into the kernel's resting bundle.
 */
export { default, type JsonTreeProps } from "./components/JsonTree.jsx";
