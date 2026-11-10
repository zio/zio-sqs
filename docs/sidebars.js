const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO SQS",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        "getting-started",
        "consuming",
        "producing",
        "serialization",
        "testing-locally",
        "reference",
        "how-to-use"
      ]
    }
  ]
};

module.exports = sidebars;
